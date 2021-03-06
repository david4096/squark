package com.tom_e_white.squark;

import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeader;
import org.apache.spark.api.java.JavaRDD;

/**
 * A {@link HtsjdkVariantsRdd} is the distributed equivalent of a htsjdk {@link
 * htsjdk.variant.vcf.VCFFileReader}. It represents a VCF file stored in a distributed filesystem,
 * and encapsulates a Spark RDD containing the variant records in it.
 *
 * <p>Use a {@link HtsjdkVariantsRddStorage} to read and write {@link HtsjdkVariantsRdd}s.
 *
 * @see HtsjdkVariantsRddStorage
 */
public class HtsjdkVariantsRdd {

  private final VCFHeader header;
  private final JavaRDD<VariantContext> variants;

  public HtsjdkVariantsRdd(VCFHeader header, JavaRDD<VariantContext> variants) {
    this.header = header;
    this.variants = variants;
  }

  public VCFHeader getHeader() {
    return header;
  }

  public JavaRDD<VariantContext> getVariants() {
    return variants;
  }
}
