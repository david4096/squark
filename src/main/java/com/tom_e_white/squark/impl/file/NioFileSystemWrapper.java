package com.tom_e_white.squark.impl.file;

import htsjdk.samtools.ExtSeekableBufferedStream;
import htsjdk.samtools.seekablestream.SeekablePathStream;
import htsjdk.samtools.seekablestream.SeekableStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import org.apache.hadoop.conf.Configuration;

public class NioFileSystemWrapper implements FileSystemWrapper {

  @Override
  public SeekableStream open(Configuration conf, String path) throws IOException {
    return new ExtSeekableBufferedStream(new SeekablePathStream(asPath(path)));
  }

  @Override
  public OutputStream create(Configuration conf, String path) throws IOException {
    return Files.newOutputStream(asPath(path));
  }

  @Override
  public boolean exists(Configuration conf, String path) {
    return Files.isRegularFile(asPath(path));
  }

  @Override
  public long getFileLength(Configuration conf, String path) throws IOException {
    return Files.size(asPath(path));
  }

  @Override
  public boolean isDirectory(Configuration conf, String path) throws IOException {
    return Files.isDirectory(asPath(path));
  }

  @Override
  public List<String> listDirectory(Configuration conf, String path) throws IOException {
    return null;
  }

  @Override
  public void concat(Configuration conf, List<String> parts, String path) throws IOException {
    try (OutputStream out = create(conf, path)) {
      for (final String part : parts) {
        Path src = asPath(part);
        Files.copy(src, out);
        Files.delete(src);
      }
    }
  }

  /**
   * Convert the given path {@link URI} to a {@link Path} object.
   *
   * @param uri the path to convert
   * @return a {@link Path} object
   */
  private static Path asPath(URI uri) {
    try {
      return Paths.get(uri);
    } catch (FileSystemNotFoundException e) {
      ClassLoader cl = Thread.currentThread().getContextClassLoader();
      if (cl == null) {
        throw e;
      }
      try {
        return FileSystems.newFileSystem(uri, new HashMap<>(), cl).provider().getPath(uri);
      } catch (IOException ex) {
        throw new RuntimeException("Cannot create filesystem for " + uri, ex);
      }
    }
  }

  /**
   * Convert the given path string to a {@link Path} object.
   *
   * @param path the path to convert
   * @return a {@link Path} object
   */
  public static Path asPath(String path) {
    URI uri = URI.create(path);
    return uri.getScheme() == null ? Paths.get(path) : asPath(uri);
  }
}
