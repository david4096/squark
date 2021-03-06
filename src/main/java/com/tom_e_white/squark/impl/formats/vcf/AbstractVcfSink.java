package com.tom_e_white.squark.impl.formats.vcf;

import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeader;
import java.io.IOException;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;

public abstract class AbstractVcfSink {
  public abstract void save(
      JavaSparkContext jsc, VCFHeader vcfHeader, JavaRDD<VariantContext> variants, String path)
      throws IOException;
}
