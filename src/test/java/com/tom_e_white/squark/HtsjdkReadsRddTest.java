package com.tom_e_white.squark;

import com.tom_e_white.squark.impl.formats.BoundedTraversalUtil;
import htsjdk.samtools.BamFileIoUtils;
import htsjdk.samtools.QueryInterval;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SamInputResource;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.SamReaderFactory;
import htsjdk.samtools.SamStreams;
import htsjdk.samtools.cram.build.CramIO;
import htsjdk.samtools.cram.ref.ReferenceSource;
import htsjdk.samtools.util.IOUtil;
import htsjdk.samtools.util.Interval;
import htsjdk.samtools.util.Locatable;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import junitparams.JUnitParamsRunner;
import junitparams.Parameters;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(JUnitParamsRunner.class)
public class HtsjdkReadsRddTest extends BaseTest {

  private Object[] parametersForTestReadAndWrite() {
    return new Object[][] {
      {"1.bam", null, ".bam", 128 * 1024, false},
      {"1.bam", null, ".bam", 128 * 1024, true},
      {"valid.cram", "valid.fasta", ".cram", 128 * 1024, false},
      {"valid_no_index.cram", "valid.fasta", ".cram", 128 * 1024, false},
      {"test.sam", null, ".sam", 128 * 1024, false},
    };
  }

  @Test
  @Parameters
  public void testReadAndWrite(
      String inputFile,
      String cramReferenceFile,
      String outputExtension,
      int splitSize,
      boolean useNio)
      throws Exception {
    String inputPath = ClassLoader.getSystemClassLoader().getResource(inputFile).toURI().toString();

    HtsjdkReadsRddStorage htsjdkReadsRddStorage =
        HtsjdkReadsRddStorage.makeDefault(jsc).splitSize(splitSize).useNio(useNio);
    File refFile = null;
    if (cramReferenceFile != null) {
      refFile = new File(ClassLoader.getSystemClassLoader().getResource(cramReferenceFile).toURI());
      String cramReferencePath = refFile.toString();
      htsjdkReadsRddStorage.referenceSourcePath(cramReferencePath);
    }

    HtsjdkReadsRdd htsjdkReadsRdd = htsjdkReadsRddStorage.read(inputPath);

    int expectedCount = getBAMRecordCount(new File(inputPath.replace("file:", "")), refFile);
    Assert.assertEquals(expectedCount, htsjdkReadsRdd.getReads().count());

    File outputFile = File.createTempFile("test", outputExtension);
    outputFile.delete();
    String outputPath = outputFile.toURI().toString();

    htsjdkReadsRddStorage.write(htsjdkReadsRdd, outputPath);

    Assert.assertEquals(expectedCount, getBAMRecordCount(outputFile, refFile));
    if (SamtoolsTestUtil.isSamtoolsAvailable()) {
      Assert.assertEquals(expectedCount, SamtoolsTestUtil.countReads(outputFile, refFile));
    }

    // check we can read what we've just written back
    Assert.assertEquals(expectedCount, htsjdkReadsRddStorage.read(outputPath).getReads().count());
  }

  private Object[] parametersForTestReadAndWriteMultiple() {
    return new Object[][] {
      {null, ".bams", false, ".bam", ".bai"},
      //{"test.fa", ".crams", false, ".cram", ".crai"}, // TODO: reinstate when we can read multiple CRAM files
      {null, ".sams", false, ".sam", null},
    };
  }

  @Test
  @Parameters
  public void testReadAndWriteMultiple(String cramReferenceFile, String outputExtension, boolean useNio, String extension, String indexExtension) throws Exception {

    HtsjdkReadsRddStorage htsjdkReadsRddStorage =
        HtsjdkReadsRddStorage.makeDefault(jsc).splitSize(40000).useNio(false);
    File refFile = null;
    if (cramReferenceFile != null) {
      refFile = new File(ClassLoader.getSystemClassLoader().getResource(cramReferenceFile).toURI());
      String cramReferencePath = refFile.toString();
      htsjdkReadsRddStorage.referenceSourcePath(cramReferencePath);
    }

    String inputPath =
        AnySamTestUtil.writeAnySamFile(
            1000, SAMFileHeader.SortOrder.coordinate, extension, indexExtension, refFile)
            .toURI()
            .toString();

    HtsjdkReadsRdd htsjdkReadsRdd = htsjdkReadsRddStorage.read(inputPath);

    Assert.assertTrue(htsjdkReadsRdd.getReads().getNumPartitions() > 1);

    int expectedCount = getBAMRecordCount(new File(inputPath.replace("file:", "")), refFile);
    Assert.assertEquals(expectedCount, htsjdkReadsRdd.getReads().count());

    File outputFile = File.createTempFile("test", outputExtension);
    outputFile.delete();
    String outputPath = outputFile.toURI().toString();

    htsjdkReadsRddStorage.write(htsjdkReadsRdd, outputPath);

    Assert.assertTrue(outputFile.isDirectory());

    int totalCount = 0;
    for (File part : outputFile.listFiles(file -> file.getName().startsWith("part-"))) {
      totalCount += getBAMRecordCount(part, refFile);
    }
    Assert.assertEquals(expectedCount, totalCount);

    if (SamtoolsTestUtil.isSamtoolsAvailable()) {
      int totalCountSamtools = 0;
      for (File part : outputFile.listFiles(file -> file.getName().startsWith("part-"))) {
        totalCountSamtools += SamtoolsTestUtil.countReads(part, refFile);
      }
      Assert.assertEquals(expectedCount, totalCountSamtools);
    }

    // check we can read what we've just written back
    Assert.assertEquals(expectedCount, htsjdkReadsRddStorage.read(outputPath).getReads().count());
  }

  @Test
  public void testReadBamsInDirectory() throws IOException, URISyntaxException {
    HtsjdkReadsRddStorage htsjdkReadsRddStorage =
        HtsjdkReadsRddStorage.makeDefault(jsc).splitSize(128 * 1024);

    // directory containing two BAM files
    File inputDir =
        new File(
            ClassLoader.getSystemClassLoader()
                .getResource("HiSeq.1mb.1RG.2k_lines.alternate.recalibrated.DIQ.sharded.bam")
                .toURI());
    String inputPath = inputDir.toURI().toString();

    HtsjdkReadsRdd htsjdkReadsRdd = htsjdkReadsRddStorage.read(inputPath);

    int expectedCount =
        getBAMRecordCount(new File(inputDir, "part-r-00000.bam"))
            + getBAMRecordCount(new File(inputDir, "part-r-00001.bam"));
    Assert.assertEquals(expectedCount, htsjdkReadsRdd.getReads().count());
  }

  private Object[] parametersForTestReadIntervals() {
    return new Object[][] {
      {
        null,
        new HtsjdkReadsTraversalParameters<>(
            Arrays.asList(
                new Interval("chr21", 5000, 9999), // includes two unpaired fragments
                new Interval("chr21", 20000, 22999)),
            false),
        ".bam",
        ".bai"
      },
      {
        null,
        new HtsjdkReadsTraversalParameters<>(
            Arrays.asList(
                new Interval("chr21", 1, 1000135) // covers whole chromosome
                ),
            false),
        ".bam",
        ".bai"
      },
      {
        null,
        new HtsjdkReadsTraversalParameters<>(
            Arrays.asList(
                new Interval("chr21", 5000, 9999), // includes two unpaired fragments
                new Interval("chr21", 20000, 22999)),
            true),
        ".bam",
        ".bai"
      },
      {null, new HtsjdkReadsTraversalParameters<>(null, true), ".bam", ".bai"},
      {
        "test.fa",
        new HtsjdkReadsTraversalParameters<>(
            Arrays.asList(
                new Interval("chr21", 5000, 9999), // includes two unpaired fragments
                new Interval("chr21", 20000, 22999)),
            false),
        ".cram",
        ".crai"
      },
      {
        "test.fa",
        new HtsjdkReadsTraversalParameters<>(
            Arrays.asList(
                new Interval("chr21", 1, 1000135) // covers whole chromosome
                ),
            false),
        ".cram",
        ".crai"
      },
      //        {
      //            "test.fa",
      //            new HtsjdkReadsTraversalParameters<>(Arrays.asList(
      //                new Interval("chr21", 5000, 9999), // includes two unpaired fragments
      //                new Interval("chr21", 20000, 22999)
      //            ), true),
      //            ".cram", ".crai"
      //        },
      //        {
      //            "test.fa",
      //            new HtsjdkReadsTraversalParameters<>(null, true),
      //            ".cram", ".crai"
      //        },
      {
        null,
        new HtsjdkReadsTraversalParameters<>(
            Arrays.asList(
                new Interval("chr21", 5000, 9999), // includes two unpaired fragments
                new Interval("chr21", 20000, 22999)),
            false),
        ".sam",
        null
      },
      {
        null,
        new HtsjdkReadsTraversalParameters<>(
            Arrays.asList(
                new Interval("chr21", 1, 1000135) // covers whole chromosome
                ),
            false),
        ".sam",
        null
      },
      {
        null,
        new HtsjdkReadsTraversalParameters<>(
            Arrays.asList(
                new Interval("chr21", 5000, 9999), // includes two unpaired fragments
                new Interval("chr21", 20000, 22999)),
            true),
        ".sam",
        null
      },
    };
  }

  @Test
  @Parameters
  public <T extends Locatable> void testReadIntervals(
      String cramReferenceFile,
      HtsjdkReadsTraversalParameters<T> traversalParameters,
      String extension,
      String indexExtension)
      throws Exception {
    HtsjdkReadsRddStorage htsjdkReadsRddStorage =
        HtsjdkReadsRddStorage.makeDefault(jsc).splitSize(40000).useNio(false);

    File refFile = null;
    if (cramReferenceFile != null) {
      refFile = new File(ClassLoader.getSystemClassLoader().getResource(cramReferenceFile).toURI());
      String cramReferencePath = refFile.toString();
      htsjdkReadsRddStorage.referenceSourcePath(cramReferencePath);
    }

    String inputPath =
        AnySamTestUtil.writeAnySamFile(
                1000, SAMFileHeader.SortOrder.coordinate, extension, indexExtension, refFile)
            .toURI()
            .toString();

    HtsjdkReadsRdd htsjdkReadsRdd = htsjdkReadsRddStorage.read(inputPath, traversalParameters);

    File inputFile = new File(inputPath.replace("file:", ""));

    int expectedCount = getBAMRecordCount(inputFile, refFile, traversalParameters);
    Assert.assertEquals(expectedCount, htsjdkReadsRdd.getReads().count());

    if (SamtoolsTestUtil.isSamtoolsAvailable()
        && !extension.equals(
            IOUtil.SAM_FILE_EXTENSION)) { // samtools can't process intervals for SAM
      int expectedCountSamtools =
          SamtoolsTestUtil.countReads(inputFile, refFile, traversalParameters);
      Assert.assertEquals(expectedCountSamtools, htsjdkReadsRdd.getReads().count());
    }
  }

  @Test(expected = IllegalArgumentException.class)
  public void testMappedOnlyFails() throws Exception {
    String inputPath =
        AnySamTestUtil.writeAnySamFile(1000, SAMFileHeader.SortOrder.coordinate).toURI().toString();

    HtsjdkReadsRddStorage htsjdkReadsRddStorage =
        HtsjdkReadsRddStorage.makeDefault(jsc).splitSize(40000).useNio(false);

    htsjdkReadsRddStorage.read(inputPath, new HtsjdkReadsTraversalParameters<>(null, false));
  }

  private static int getBAMRecordCount(final File bamFile) throws IOException {
    return getBAMRecordCount(bamFile, null);
  }

  private static int getBAMRecordCount(final File bamFile, File refFile) throws IOException {
    return getBAMRecordCount(bamFile, refFile, null);
  }

  private static <T extends Locatable> int getBAMRecordCount(
      final File bamFile, File refFile, HtsjdkReadsTraversalParameters<T> traversalParameters)
      throws IOException {

    // test file contents is consistent with extension
    try (InputStream in = new BufferedInputStream(new FileInputStream(bamFile))) {
      if (bamFile.getName().endsWith(BamFileIoUtils.BAM_FILE_EXTENSION)) {
          Assert.assertTrue("Not a BAM file", SamStreams.isBAMFile(in));
      } else if (bamFile.getName().endsWith(CramIO.CRAM_FILE_EXTENSION)) {
        Assert.assertTrue("Not a CRAM file", SamStreams.isCRAMFile(in));
      } else if (bamFile.getName().endsWith(IOUtil.SAM_FILE_EXTENSION)) {
        Assert.assertTrue("Not a SAM file", in.read() == '@');
      } else {
        Assert.fail("File is not BAM, CRAM or SAM.");
    }
  }

    ReferenceSource referenceSource = refFile == null ? null : new ReferenceSource(refFile);
    int recCount = 0;

    if (bamFile.getName().endsWith(IOUtil.SAM_FILE_EXTENSION)) {
      // we can't call query() on SamReader for SAM files, so we have to do interval filtering here
      try (SamReader samReader =
          SamReaderFactory.makeDefault()
              .referenceSource(referenceSource)
              .open(SamInputResource.of(bamFile))) {
        for (SAMRecord record : samReader) {
          Assert.assertNotNull(record.getHeader());
          if (traversalParameters == null) {
            recCount++;
          } else {
            if (traversalParameters.getIntervalsForTraversal() != null) {
              for (T interval : traversalParameters.getIntervalsForTraversal()) {
                if (interval.overlaps(record)) {
                  recCount++;
                  break;
                }
              }
            }
            if (traversalParameters.getTraverseUnplacedUnmapped()
                && record.getReadUnmappedFlag()
                && record.getAlignmentStart() == SAMRecord.NO_ALIGNMENT_START) {
              recCount++;
            }
          }
        }
      }
      return recCount;
    }

    try (SamReader bamReader =
        SamReaderFactory.makeDefault()
            .referenceSource(referenceSource)
            .open(SamInputResource.of(bamFile))) {
      Iterator<SAMRecord> it;
      if (traversalParameters == null) {
        it = bamReader.iterator();
      } else if (traversalParameters.getIntervalsForTraversal() == null) {
        it = Collections.emptyIterator();
      } else {
        SAMSequenceDictionary sequenceDictionary =
            bamReader.getFileHeader().getSequenceDictionary();
        QueryInterval[] queryIntervals =
            BoundedTraversalUtil.prepareQueryIntervals(
                traversalParameters.getIntervalsForTraversal(), sequenceDictionary);
        it = bamReader.queryOverlapping(queryIntervals);
      }
      recCount += size(it);
    }

    if (traversalParameters != null && traversalParameters.getTraverseUnplacedUnmapped()) {
      try (SamReader bamReader =
          SamReaderFactory.makeDefault()
              .referenceSource(referenceSource)
              .open(SamInputResource.of(bamFile))) {
        Iterator<SAMRecord> it = bamReader.queryUnmapped();
        recCount += size(it);
      }
    }

    return recCount;
  }

  private static int size(Iterator<SAMRecord> iterator) {
    int count = 0;
    while (iterator.hasNext()) {
      SAMRecord next = iterator.next();
      Assert.assertNotNull(next.getHeader());
      count++;
    }
    return count;
  }
}
