/****************************************************************************
 * Copyright (C) 2016-2019 Maschell
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ****************************************************************************/
package de.mas.wiiu.jnus.utils.cryptography;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;

import de.mas.wiiu.jnus.entities.Ticket;
import de.mas.wiiu.jnus.entities.content.Content;
import de.mas.wiiu.jnus.utils.ByteArrayBuffer;
import de.mas.wiiu.jnus.utils.CheckSumWrongException;
import de.mas.wiiu.jnus.utils.HashUtil;
import de.mas.wiiu.jnus.utils.StreamUtils;
import de.mas.wiiu.jnus.utils.Utils;
import lombok.extern.java.Log;

@Log
public class NUSDecryption extends AESDecryption {
    public NUSDecryption(byte[] AESKey, byte[] IV) {
        super(AESKey, IV);
    }

    public NUSDecryption(Ticket ticket) {
        this(ticket.getDecryptedKey(), ticket.getIV());
    }

    private byte[] decryptFileChunk(byte[] blockBuffer, int BLOCKSIZE, byte[] IV) {
        return decryptFileChunk(blockBuffer, 0, BLOCKSIZE, IV);
    }

    private byte[] decryptFileChunk(byte[] blockBuffer, int offset, int BLOCKSIZE, byte[] IV) {
        if (IV != null) {
            setIV(IV);
            init();
        }
        return decrypt(blockBuffer, offset, BLOCKSIZE);
    }

    public void decryptFileStream(InputStream inputStream, OutputStream outputStream, long fileOffset, long filesize, byte[] IV, byte[] h3hash,
            long expectedSizeForHash) throws IOException, CheckSumWrongException {
        MessageDigest sha1 = null;
        MessageDigest sha1fallback = null;

        if (h3hash != null) {
            try {
                sha1 = MessageDigest.getInstance("SHA1");
                sha1fallback = MessageDigest.getInstance("SHA1");
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }

        int BLOCKSIZE = 0x8000;

        byte[] blockBuffer = new byte[BLOCKSIZE];

        int inBlockBuffer;
        long written = 0;
        long writtenFallback = 0;

        try {
            ByteArrayBuffer overflow = new ByteArrayBuffer(BLOCKSIZE);

            // We can only decrypt multiples of 16. So we need to align it.
            long toRead = Utils.align(filesize, 16);

            do {

                int curReadSize = BLOCKSIZE;
                if (toRead < BLOCKSIZE) {
                    curReadSize = (int) toRead;
                }

                inBlockBuffer = StreamUtils.getChunkFromStream(inputStream, blockBuffer, overflow, curReadSize);

                byte[] output = decryptFileChunk(blockBuffer, (int) Utils.align(inBlockBuffer, 16), IV);

                if (inBlockBuffer == BLOCKSIZE) {
                    IV = Arrays.copyOfRange(blockBuffer, BLOCKSIZE - 16, BLOCKSIZE);
                }

                int toWrite = inBlockBuffer;

                if ((written + inBlockBuffer) > filesize) {
                    toWrite = (int) (filesize - written);
                }

                written += toWrite;
                toRead -= toWrite;

                outputStream.write(output, 0, toWrite);

                if (sha1 != null && sha1fallback != null) {
                    sha1.update(output, 0, toWrite);

                    // In some cases it's using the hash of the whole .app file instead of the part
                    // that's been actually used.
                    long toFallback = inBlockBuffer;
                    if (writtenFallback + toFallback > expectedSizeForHash) {
                        toFallback = expectedSizeForHash - writtenFallback;
                    }
                    sha1fallback.update(output, 0, (int) toFallback);
                    writtenFallback += toFallback;
                }

                if (written >= filesize && h3hash == null) {
                    break;
                }
            } while (inBlockBuffer == BLOCKSIZE);

            if (sha1 != null && sha1fallback != null) {
                long missingInHash = expectedSizeForHash - writtenFallback;
                if (missingInHash > 0) {
                    sha1fallback.update(new byte[(int) missingInHash]);
                }

                byte[] calculated_hash1 = sha1.digest();
                byte[] calculated_hash2 = sha1fallback.digest();
                byte[] expected_hash = h3hash;
                if (!Arrays.equals(calculated_hash1, expected_hash) && !Arrays.equals(calculated_hash2, expected_hash)) {
                    throw new CheckSumWrongException("hash checksum failed ", calculated_hash1, expected_hash);
                } else {
                    log.finest("Hash DOES match saves output stream.");
                }

            }
        } finally {
            StreamUtils.closeAll(inputStream, outputStream);
        }
        if (written < filesize) {
            throw new IOException("Failed to read. Missing " + (filesize - written));
        }
    }

    public void decryptFileStreamHashed(InputStream inputStream, OutputStream outputStream, long fileoffset, long filesize, byte[] h3Hash)
            throws IOException, CheckSumWrongException, NoSuchAlgorithmException {
        int BLOCKSIZE = 0x10000;
        int HASHBLOCKSIZE = 0xFC00;

        long writeSize = HASHBLOCKSIZE;

        long block = (fileoffset / HASHBLOCKSIZE);
        long soffset = fileoffset - (fileoffset / HASHBLOCKSIZE * HASHBLOCKSIZE);

        if (soffset + filesize > writeSize) {
            writeSize = writeSize - soffset;

        }

        byte[] encryptedBlockBuffer = new byte[BLOCKSIZE];
        ByteArrayBuffer overflow = new ByteArrayBuffer(BLOCKSIZE);
        long wrote = 0;
        int inBlockBuffer = 0;

        try {
            do {
                inBlockBuffer = StreamUtils.getChunkFromStream(inputStream, encryptedBlockBuffer, overflow, BLOCKSIZE);
                if (writeSize > filesize) writeSize = filesize;

                if (inBlockBuffer != BLOCKSIZE) {
                    throw new IOException("wasn't able to read  " + BLOCKSIZE);
                }

                byte[] output;
                try {
                    output = decryptFileChunkHash(encryptedBlockBuffer, (int) block, h3Hash);
                } catch (CheckSumWrongException | NoSuchAlgorithmException e) {
                    throw e;
                }

                if ((wrote + writeSize) > filesize) {
                    writeSize = (int) (filesize - wrote);
                }

                try {
                    outputStream.write(output, (int) (0 + soffset), (int) writeSize);
                } catch (IOException e) {
                    if (e.getMessage().equals("Pipe closed")) {
                        break;
                    }
                    e.printStackTrace();
                    throw e;
                }
                wrote += writeSize;

                block++;

                if (soffset > 0) {
                    writeSize = HASHBLOCKSIZE;
                    soffset = 0;
                }
            } while (wrote < filesize && (inBlockBuffer == BLOCKSIZE));
            log.finest("Decryption okay");
        } finally {
            StreamUtils.closeAll(inputStream, outputStream);
        }
    }

    private byte[] decryptFileChunkHash(byte[] blockBuffer, int block, byte[] h3_hashes) throws CheckSumWrongException, NoSuchAlgorithmException {
        int hashSize = 0x400;
        int blocksize = 0xFC00;

        byte[] hashes = decryptFileChunk(blockBuffer, hashSize, new byte[16]);

        int H0_start = (block % 16) * 20;

        byte[] IV = Arrays.copyOfRange(hashes, H0_start, H0_start + 16);
        byte[] output = decryptFileChunk(blockBuffer, hashSize, blocksize, IV);

        HashUtil.checkFileChunkHashes(hashes, h3_hashes, output, block);

        return output;
    }

    public boolean decryptStreamsHashed(InputStream inputStream, OutputStream outputStream, long offset, long size, Optional<byte[]> h3HashHashed)
            throws IOException, CheckSumWrongException, NoSuchAlgorithmException {
        try {
            byte[] h3 = h3HashHashed.orElseThrow(() -> new FileNotFoundException("h3 hash not found."));
            decryptFileStreamHashed(inputStream, outputStream, offset, size, h3);
        } finally {
            StreamUtils.closeAll(inputStream, outputStream);
        }

        return true;
    }

    public boolean decryptStreamsNonHashed(InputStream inputStream, OutputStream outputStream, long offset, long size, Content content, byte[] IV,
            boolean partial) throws IOException, CheckSumWrongException {
        try {
            byte[] h3Hash = content.getSHA2Hash();
            // Ignore the h3hash if we don't read the whole file.
            if (partial) {
                h3Hash = null;
            }
            decryptFileStream(inputStream, outputStream, offset, size, IV, h3Hash, content.getEncryptedFileSize());
        } finally {
            StreamUtils.closeAll(inputStream, outputStream);
        }

        return true;
    }
}
