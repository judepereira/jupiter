package com.judepereira.aide.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.File;
import java.util.Objects;

@AllArgsConstructor
@Data
public class Dir {
    private File file;
    private boolean root;

    public Dir(File file) {
        this(file, false);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Dir dir = (Dir) o;
        return Objects.equals(file.getAbsolutePath(), dir.file.getAbsolutePath());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(file.getAbsolutePath());
    }

    @Override
    public String toString() {
        return root? file.getAbsolutePath(): file.getName();
    }
}
