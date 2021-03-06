package com.tom_e_white.squark.impl.file;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.FilenameUtils;
import org.apache.hadoop.conf.Configuration;

public class Merger {

  private final FileSystemWrapper fileSystemWrapper;

  public Merger() {
    fileSystemWrapper = new HadoopFileSystemWrapper();
  }

  public void mergeParts(Configuration conf, String partDirectory, String outputFile)
      throws IOException {
    List<String> parts = fileSystemWrapper.listDirectory(conf, partDirectory);
    List<String> filteredParts =
        parts
            .stream()
            .filter(
                f ->
                    !(FilenameUtils.getBaseName(f).startsWith(".")
                        || FilenameUtils.getBaseName(f).startsWith("_")))
            .collect(Collectors.toList());
    fileSystemWrapper.concat(conf, filteredParts, outputFile);
  }
}
