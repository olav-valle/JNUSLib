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
package de.mas.wiiu.jnus;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.Optional;
import java.util.function.Supplier;

import de.mas.wiiu.jnus.entities.Ticket;
import de.mas.wiiu.jnus.entities.content.Content;
import de.mas.wiiu.jnus.entities.fst.FST;
import de.mas.wiiu.jnus.entities.fst.FSTEntry;
import de.mas.wiiu.jnus.implementations.FSTDataProviderNUSTitle;
import de.mas.wiiu.jnus.interfaces.FSTDataProvider;
import de.mas.wiiu.jnus.interfaces.NUSDataProvider;
import de.mas.wiiu.jnus.utils.StreamUtils;
import de.mas.wiiu.jnus.utils.cryptography.AESDecryption;

public class NUSTitleLoader {
    private NUSTitleLoader() {
        // should be empty
    }

    public static NUSTitle loadNusTitle(NUSTitleConfig config, Supplier<NUSDataProvider> dataProviderFunction) throws IOException, ParseException {
        NUSDataProvider dataProvider = dataProviderFunction.get();

        NUSTitle result = new NUSTitle(dataProvider);

        if (config.isNoDecryption()) {
            return result;
        }

        Ticket ticket = null;
        if (config.isTicketNeeded()) {
            ticket = config.getTicket();
            if (ticket == null) {
                Optional<byte[]> ticketOpt = dataProvider.getRawTicket();
                if (ticketOpt.isPresent()) {
                    ticket = Ticket.parseTicket(ticketOpt.get(), config.getCommonKey());
                }
            }
            if (ticket == null) {
                new ParseException("Failed to get ticket data", 0);
            }
            result.setTicket(Optional.of(ticket));
        }

        // If we have just content, we don't have a FST.
        if (result.getTMD().getAllContents().size() == 1) {
            // The only way to check if the key is right, is by trying to decrypt the whole thing.
            FSTDataProvider dp = new FSTDataProviderNUSTitle(result);
            for (FSTEntry children : dp.getRoot().getChildren()) {
                dp.readFile(children);
            }

            return result;
        }
        // If we have more than one content, the index 0 is the FST.
        Content fstContent = result.getTMD().getContentByIndex(0);

        InputStream fstContentEncryptedStream = dataProvider.readContentAsStream(fstContent);

        byte[] fstBytes = StreamUtils.getBytesFromStream(fstContentEncryptedStream, (int) fstContent.getEncryptedFileSize());

        if (fstContent.isEncrypted()) {
            AESDecryption aesDecryption = new AESDecryption(ticket.getDecryptedKey(), new byte[0x10]);
            if (fstBytes.length % 0x10 != 0) {
                throw new IOException("FST length is not align to 16");
            }
            fstBytes = aesDecryption.decrypt(fstBytes);
        }

        FST fst = FST.parseFST(fstBytes);
        result.setFST(Optional.of(fst));

        // The dataprovider may need the FST to calculate the offset of a content
        // on the partition.
        dataProvider.setFST(fst);

        return result;
    }
}
