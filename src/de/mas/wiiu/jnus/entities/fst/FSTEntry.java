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
package de.mas.wiiu.jnus.entities.fst;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import de.mas.wiiu.jnus.entities.content.Content;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;

/**
 * Represents one FST Entry
 * 
 * @author Maschell
 *
 */
public class FSTEntry {
    public static final byte FSTEntry_DIR = (byte) 0x01;
    public static final byte FSTEntry_notInNUS = (byte) 0x80;

    private String filename = null;
    private final Supplier<String> filenameSupplier;

    @Getter private final Optional<FSTEntry> parent;

    @Getter private final List<FSTEntry> children = new ArrayList<>();

    @Getter private final short flags;

    @Getter private final long fileSize;
    @Getter private final long fileOffset;

    @Getter private final boolean isDir;
    @Getter private final boolean isRoot;
    @Getter private final boolean isNotInPackage;

    @Getter private final short contentIndex;

    protected FSTEntry(FSTEntryParam fstParam) {
        this.filenameSupplier = fstParam.getFileNameSupplier();
        this.flags = fstParam.getFlags();
        this.parent = fstParam.getParent();

        this.fileSize = fstParam.getFileSize();
        this.fileOffset = fstParam.getFileOffset();

        this.isDir = fstParam.isDir();
        this.isRoot = fstParam.isRoot();
        this.isNotInPackage = fstParam.isNotInPackage();
        this.contentIndex = fstParam.getContentIndex();
    }

    /**
     * Creates and returns a new FST Entry
     * 
     * @return
     */
    public static FSTEntry getRootFSTEntry() {
        FSTEntryParam param = new FSTEntryParam();
        param.setRoot(true);
        param.setDir(true);
        return new FSTEntry(param);
    }

    public static FSTEntry createFSTEntry(@NonNull FSTEntry parent, @NonNull String filename, @NonNull Content content) {
        FSTEntryParam param = new FSTEntryParam();
        param.setFileNameSupplier(() -> filename);
        param.setFileSize(content.getDecryptedFileSize());
        param.setDir(false);
        param.setParent(Optional.of(parent));
        return new FSTEntry(param);
    }

    public String getFilename() {
        if (filename == null) {
            filename = filenameSupplier.get();
        }
        return filename;
    }

    public String getFullPath() {
        return getPath() + getFilename();
    }

    private StringBuilder getPathInternal() {
        if (parent.isPresent()) {
            FSTEntry par = parent.get();
            return par.getPathInternal().append(par.getFilename()).append('/');
        }
        return new StringBuilder();
    }

    public String getPath() {
        return getPathInternal().toString();
    }

    public int getEntryCount() {
        int count = 1;
        for (FSTEntry entry : getChildren()) {
            count += entry.getEntryCount();
        }
        return count;
    }

    public List<FSTEntry> getDirChildren() {
        return getDirChildren(false);
    }

    public List<FSTEntry> getDirChildren(boolean all) {
        List<FSTEntry> result = new ArrayList<>();
        for (FSTEntry child : getChildren()) {
            if (child.isDir() && (all || !child.isNotInPackage())) {
                result.add(child);
            }
        }
        return result;
    }

    public List<FSTEntry> getFileChildren() {
        return getFileChildren(false);
    }

    public List<FSTEntry> getFileChildren(boolean all) {
        List<FSTEntry> result = new ArrayList<>();
        for (FSTEntry child : getChildren()) {
            if ((all && !child.isDir() || !child.isDir())) {
                result.add(child);
            }
        }
        return result;
    }

    public void printRecursive(int space) {
        printRecursive(System.out, space);
    }

    public void printRecursive(PrintStream out, int space) {
        for (int i = 0; i < space; i++) {
            out.print(" ");
        }
        out.print(getFilename());
        if (isNotInPackage()) {
            out.print(" (not in package)");
        }
        out.println();
        for (FSTEntry child : getDirChildren(true)) {
            child.printRecursive(space + 5);
        }
        for (FSTEntry child : getFileChildren(true)) {
            child.printRecursive(space + 5);
        }
    }

    @Override
    public String toString() {
        return "FSTEntry [filename=" + getFilename() + ", path=" + getPath() + ", flags=" + flags + ", filesize=" + fileSize + ", fileoffset=" + fileOffset
                + ", isDir=" + isDir + ", isRoot=" + isRoot + ", notInPackage=" + isNotInPackage + "]";
    }

    @Data
    protected static class FSTEntryParam {
        private Supplier<String> fileNameSupplier = () -> "";
        private Optional<FSTEntry> parent = Optional.empty();

        private short flags;

        private long fileSize = 0;
        private long fileOffset = 0;

        private boolean isDir = false;
        private boolean isRoot = false;
        private boolean notInPackage = false;

        private short contentIndex = 0;
    }

    public void addChildren(FSTEntry entry) {
        this.getChildren().add(entry);
    }

}
