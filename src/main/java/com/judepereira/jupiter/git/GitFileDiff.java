package com.judepereira.jupiter.git;

import lombok.Value;

@Value
public class GitFileDiff {
    String path;
    String diff;
}
