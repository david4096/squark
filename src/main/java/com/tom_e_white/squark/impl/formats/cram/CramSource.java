package com.tom_e_white.squark.impl.formats.cram;

import com.google.common.collect.Iterators;
import com.tom_e_white.squark.HtsjdkReadsTraversalParameters;
import com.tom_e_white.squark.impl.file.FileSplitInputFormat;
import com.tom_e_white.squark.impl.file.HadoopFileSystemWrapper;
import com.tom_e_white.squark.impl.file.NioFileSystemWrapper;
import com.tom_e_white.squark.impl.formats.AutocloseIteratorWrapper;
import com.tom_e_white.squark.impl.formats.BoundedTraversalUtil;
import com.tom_e_white.squark.impl.formats.SerializableHadoopConfiguration;
import com.tom_e_white.squark.impl.formats.bgzf.BgzfVirtualFilePointerUtil;
import com.tom_e_white.squark.impl.formats.sam.AbstractSamSource;
import com.tom_e_white.squark.impl.formats.sam.SamFormat;
import htsjdk.samtools.AbstractBAMFileIndex;
import htsjdk.samtools.BAMFileReader;
import htsjdk.samtools.BAMFileSpan;
import htsjdk.samtools.BAMIndex;
import htsjdk.samtools.CRAMCRAIIndexer;
import htsjdk.samtools.CRAMFileReader;
import htsjdk.samtools.CRAMIntervalIterator;
import htsjdk.samtools.Chunk;
import htsjdk.samtools.CramContainerHeaderIterator;
import htsjdk.samtools.QueryInterval;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReader.PrimitiveSamReaderToSamReaderAdapter;
import htsjdk.samtools.ValidationStringency;
import htsjdk.samtools.cram.CRAIEntry;
import htsjdk.samtools.cram.CRAIIndex;
import htsjdk.samtools.cram.ref.ReferenceSource;
import htsjdk.samtools.cram.structure.Container;
import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.samtools.util.Locatable;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.broadcast.Broadcast;
import scala.Tuple2;

public class CramSource extends AbstractSamSource implements Serializable {

  public CramSource() {
    super(new HadoopFileSystemWrapper());
  }

  @Override
  public SamFormat getSamFormat() {
    return SamFormat.CRAM;
  }

  @Override
  public <T extends Locatable> JavaRDD<SAMRecord> getReads(
      JavaSparkContext jsc,
      String path,
      int splitSize,
      HtsjdkReadsTraversalParameters<T> traversalParameters,
      ValidationStringency validationStringency,
      String referenceSourcePath)
      throws IOException {
    if (traversalParameters != null
        && traversalParameters.getIntervalsForTraversal() == null
        && !traversalParameters.getTraverseUnplacedUnmapped()) {
      throw new IllegalArgumentException("Traversing mapped reads only is not supported.");
    }

    // Use Hadoop FileSystem API to maintain file locality by using Hadoop's FileInputFormat

    final Configuration conf = jsc.hadoopConfiguration();
    if (splitSize > 0) {
      conf.setInt(FileInputFormat.SPLIT_MAXSIZE, splitSize);
    }

    // store paths (not full URIs) to avoid differences in scheme - this could be improved
    Map<String, List<Long>> pathToContainerOffsets = new LinkedHashMap<>();
    if (fileSystemWrapper.isDirectory(conf, path)) {
      List<String> paths =
          fileSystemWrapper
              .listDirectory(conf, path)
              .stream()
              .filter(SamFormat.CRAM::fileMatches)
              .collect(Collectors.toList());
      for (String p : paths) {
        long cramFileLength = fileSystemWrapper.getFileLength(conf, p);
        List<Long> containerOffsets = getContainerOffsetsFromIndex(conf, p, cramFileLength);
        pathToContainerOffsets.put(URI.create(p).getPath(), containerOffsets);
      }
    } else {
      long cramFileLength = fileSystemWrapper.getFileLength(conf, path);
      List<Long> containerOffsets = getContainerOffsetsFromIndex(conf, path, cramFileLength);
      pathToContainerOffsets.put(URI.create(path).getPath(), containerOffsets);
    }
    Broadcast<Map<String, List<Long>>> containerOffsetsBroadcast =
        jsc.broadcast(pathToContainerOffsets);

    SerializableHadoopConfiguration confSer = new SerializableHadoopConfiguration(conf);
    Broadcast<HtsjdkReadsTraversalParameters<T>> traversalParametersBroadcast =
        traversalParameters == null ? null : jsc.broadcast(traversalParameters);
    return jsc.newAPIHadoopFile(path, FileSplitInputFormat.class, Void.class, FileSplit.class, conf)
        .flatMap(
            (FlatMapFunction<Tuple2<Void, FileSplit>, SAMRecord>)
                t2 -> {
                  FileSplit fileSplit = t2._2();
                  String p = fileSplit.getPath().toUri().toString();
                  Map<String, List<Long>> pathToOffsets = containerOffsetsBroadcast.getValue();
                  List<Long> offsets = pathToOffsets.get(fileSplit.getPath().toUri().getPath());
                  long newStart = nextContainerOffset(offsets, fileSplit.getStart());
                  long newEnd =
                      nextContainerOffset(offsets, fileSplit.getStart() + fileSplit.getLength());
                  if (newStart == newEnd) {
                    return Collections.emptyIterator();
                  }
                  Configuration c = confSer.getConf();
                  SamReader samReader =
                      createSamReader(c, p, validationStringency, referenceSourcePath);
                  CRAMFileReader cramFileReader = createCramFileReader(samReader);
                  // TODO: test edge cases
                  // Subtract one from end since CRAMIterator's boundaries are inclusive
                  Chunk readRange =
                      new Chunk(
                          BgzfVirtualFilePointerUtil.makeFilePointer(newStart),
                          BgzfVirtualFilePointerUtil.makeFilePointer(newEnd - 1));
                  BAMFileSpan splitSpan = new BAMFileSpan(readRange);
                  HtsjdkReadsTraversalParameters<T> traversal =
                      traversalParametersBroadcast == null
                          ? null
                          : traversalParametersBroadcast.getValue();
                  if (traversal != null) {
                    SAMFileHeader header = samReader.getFileHeader();
                    SAMSequenceDictionary dict = header.getSequenceDictionary();
                    BAMIndex idx = samReader.indexing().getIndex();
                    Iterator<SAMRecord> intervalReadsIterator;
                    if (traversal.getIntervalsForTraversal() == null) {
                      intervalReadsIterator = Collections.emptyIterator();
                      samReader.close(); // not needed
                    } else {
                      QueryInterval[] queryIntervals =
                          BoundedTraversalUtil.prepareQueryIntervals(
                              traversal.getIntervalsForTraversal(), dict);
                      BAMFileSpan span = BAMFileReader.getFileSpan(queryIntervals, idx);
                      span = (BAMFileSpan) span.removeContentsBefore(splitSpan);
                      span = (BAMFileSpan) span.removeContentsAfter(splitSpan);
                      SeekableStream ss = fileSystemWrapper.open(c, p);
                      // TODO: should go through FileSystemWrapper
                      ReferenceSource referenceSource =
                          new ReferenceSource(NioFileSystemWrapper.asPath(referenceSourcePath));
                      intervalReadsIterator =
                          new AutocloseIteratorWrapper<>(
                              new CRAMIntervalIterator(
                                  queryIntervals,
                                  false,
                                  idx,
                                  ss,
                                  referenceSource,
                                  validationStringency,
                                  span.toCoordinateArray()),
                              ss);
                      samReader.close(); // not needed
                    }

                    // add on unplaced unmapped reads if there are any in this range
                    if (traversal.getTraverseUnplacedUnmapped()) {
                      long startOfLastLinearBin = idx.getStartOfLastLinearBin();
                      long noCoordinateCount = ((AbstractBAMFileIndex) idx).getNoCoordinateCount();
                      if (startOfLastLinearBin != -1 && noCoordinateCount > 0) {
                        long unplacedUnmappedStart = startOfLastLinearBin;
                        if (readRange.getChunkStart() <= unplacedUnmappedStart
                            && unplacedUnmappedStart < readRange.getChunkEnd()) { // TODO correct?
                          SamReader unplacedUnmappedReadsSamReader =
                              createSamReader(c, p, validationStringency, referenceSourcePath);
                          Iterator<SAMRecord> unplacedUnmappedReadsIterator =
                              new AutocloseIteratorWrapper<>(
                                  createCramFileReader(unplacedUnmappedReadsSamReader)
                                      .queryUnmapped(),
                                  unplacedUnmappedReadsSamReader);
                          return Iterators.concat(
                              intervalReadsIterator, unplacedUnmappedReadsIterator);
                        }
                      }
                    }
                    return intervalReadsIterator;
                  } else {
                    return new AutocloseIteratorWrapper<>(
                        cramFileReader.getIterator(splitSpan), samReader);
                  }
                });
  }

  private List<Long> getContainerOffsetsFromIndex(
      Configuration conf, String path, long cramFileLength) throws IOException {
    try (SeekableStream in = findIndex(conf, path)) {
      if (in == null) {
        return getContainerOffsetsFromFile(conf, path, cramFileLength);
      }
      List<Long> containerOffsets = new ArrayList<>();
      CRAIIndex index = CRAMCRAIIndexer.readIndex(in);
      for (CRAIEntry entry : index.getCRAIEntries()) {
        containerOffsets.add(entry.containerStartOffset);
      }
      containerOffsets.add(cramFileLength);
      return containerOffsets;
    }
  }

  private List<Long> getContainerOffsetsFromFile(
      Configuration conf, String path, long cramFileLength) throws IOException {
    try (SeekableStream seekableStream = fileSystemWrapper.open(conf, path)) {
      CramContainerHeaderIterator it = new CramContainerHeaderIterator(seekableStream);
      List<Long> containerOffsets = new ArrayList<Long>();
      while (it.hasNext()) {
        Container container = it.next();
        containerOffsets.add(container.offset);
      }
      containerOffsets.add(cramFileLength);
      return containerOffsets;
    }
  }

  private static long nextContainerOffset(List<Long> containerOffsets, long position) {
    int index = Collections.binarySearch(containerOffsets, position);
    long offset;
    if (index >= 0) {
      offset = containerOffsets.get(index);
    } else {
      int insertionPoint = -index - 1;
      if (insertionPoint == containerOffsets.size()) {
        throw new IllegalStateException(
            "Could not find position "
                + position
                + " in "
                + "container offsets: "
                + containerOffsets);
      }
      offset = containerOffsets.get(insertionPoint);
    }
    return offset;
  }

  private CRAMFileReader createCramFileReader(SamReader samReader) throws IOException {
    return (CRAMFileReader) ((PrimitiveSamReaderToSamReaderAdapter) samReader).underlyingReader();
  }
}
