package ucar.nc2.jni.netcdf;

import org.junit.*;
import org.junit.experimental.categories.Category;
import ucar.ma2.Array;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.MAMath;
import ucar.nc2.*;
import ucar.nc2.constants.CDM;
import ucar.nc2.iosp.hdf5.H5iosp;
import ucar.nc2.iosp.hdf5.TestH5;
import ucar.nc2.util.CompareNetcdf2;
import ucar.nc2.util.DebugFlags;
import ucar.nc2.util.DebugFlagsImpl;
import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.test.util.CompareNetcdf;
import ucar.unidata.test.util.NeedsCdmUnitTest;
import ucar.unidata.test.util.TestDir;

import java.io.*;
import java.util.Formatter;

/**
 * Test JNI netcdf-4 iosp
 * Compare reading with native java reading
 *
 * @author caron
 * @since 7/3/12
 */
@Category(NeedsCdmUnitTest.class)
public class TestNc4IospReading {
  private boolean showCompareResults = true;
  private int countNotOK = 0;

  @Before
  public void setLibrary() {
    // Ignore this class's tests if NetCDF-4 isn't present.
    // We're using @Before because it shows these tests as being ignored.
    // @BeforeClass shows them as *non-existent*, which is not what we want.
    Assume.assumeTrue("NetCDF-4 C library not present.", Nc4Iosp.isClibraryPresent());
  }

    // i dont trust our code, compare with jni reading
  @Test
  public void sectionStringsWithFilter() throws IOException, InvalidRangeException {
    String filename = TestH5.testDir + "StringsWFilter.h5";
    try (NetcdfFile ncfile = NetcdfFile.open(filename);
      NetcdfFile jni = openJni(filename)) {

      Variable v = ncfile.findVariable("/sample/ids");
      assert v != null;
      int[] shape = v.getShape();
      Assert.assertEquals(1, shape.length);
      Assert.assertEquals(3107, shape[0]);

      Array dataSection = v.read("700:900:2");      // make sure to go acrross a chunk boundary
      Assert.assertEquals(1, dataSection.getRank());
      Assert.assertEquals(101, dataSection.getShape()[0]);

      Variable v2 = jni.findVariable("/sample/ids");
      assert v2 != null;
      int[] shape2 = v2.getShape();
      Assert.assertEquals(1, shape2.length);
      Assert.assertEquals(3107, shape2[0]);

      Array dataSection2 = v2.read("700:900:2");
      Assert.assertEquals(1, dataSection2.getRank());
      Assert.assertEquals(101, dataSection2.getShape()[0]);

      CompareNetcdf.compareData(dataSection, dataSection2);
    }
  }

  @Test
  public void testReadSubsection() throws IOException, InvalidRangeException {
    String location = TestDir.cdmUnitTestDir + "formats/netcdf4/ncom_relo_fukushima_1km_tmp_2011040800_t000.nc4";
    try (NetcdfFile ncfile = NetcdfFile.open(location);
         NetcdfFile jni = openJni(location)) {

      jni.setLocation(location + " (jni)");

      // float salinity(time=1, depth=40, lat=667, lon=622);
      Array data1 = read(ncfile, "salinity", "0,11:12,22,:");
      //NCdumpW.printArray(data1);
      System.out.printf("Read from jni%n");
      Array data2 = read(jni, "salinity", "0,11:12,22,:");
      assert MAMath.isEqual(data1, data2);
      System.out.printf("data is equal%n");
    }
  }

  private Array read(NetcdfFile ncfile, String vname, String section) throws IOException, InvalidRangeException {
    Variable v = ncfile.findVariable(vname);
    assert v != null;
    return v.read(section) ;
  }

  @Test
  public void testNestedStructure() throws IOException {
    doCompare(TestDir.cdmUnitTestDir + "formats/netcdf4/testNestedStructure.nc", true, false, true);
  }

  // @Test
  public void timeRead() throws IOException {
    String location = TestDir.cdmUnitTestDir+"/NARR/narr-TMP-200mb_221_yyyymmdd_hh00_000.grb.grb2.nc4";  // file not found

    try (NetcdfFile jni = openJni(location)) {
      Variable v = jni.findVariable("time");
      long start = System.currentTimeMillis();
      Array data = v.read();
      long took = System.currentTimeMillis() - start;
      System.out.printf(" jna took= %d msecs size=%d%n", took, data.getSize());
    }

    try (NetcdfFile ncfile = NetcdfFile.open(location)) {
      Variable v = ncfile.findVariable("time");
      long start = System.currentTimeMillis();
      Array data = v.read();
      long took = System.currentTimeMillis() - start;
      System.out.printf(" java took= %d msecs size=%d%n", took, data.getSize());
    }
  }

  @Test
  public void fractalHeapProblem() throws IOException {
    String filename = TestDir.cdmUnitTestDir + "formats/netcdf4/espresso_his_20130913_0000_0007.nc";
    System.out.printf("***READ %s%n", filename);
    doCompare(filename, false, false, false);

    try (NetcdfFile ncfile = NetcdfFile.open(filename)) {
      Assert.assertNotNull(ncfile.findVariable("h"));
    }
  }

  /*
    ** Missing dim phony_dim_0 = 15; not in file2
    ...
   */
  // @Test
  public void problem() throws IOException {
    String filename = TestDir.cdmUnitTestDir + "formats\\hdf5\\OMI-Aura_L2G-OMCLDRRG_2007m0105_v003-2008m0105t101212.he5";
    System.out.printf("***READ %s%n", filename);
    doCompare(filename, false, false, false);
  }

  @BeforeClass
  public static void setupClass() {
    DebugFlags debugFlags = new DebugFlagsImpl();
    debugFlags.set("H5iosp/read", true);
    debugFlags.set("H5iosp/filePos", true);
    debugFlags.set("H5iosp/Heap", true);
    debugFlags.set("H5iosp/filter", true);
    debugFlags.set("H5iosp/filterIndexer", true);
    debugFlags.set("H5iosp/chunkIndexer", true);
    debugFlags.set("H5iosp/vlen", true);
    debugFlags.set("HdfEos/turnOff", true);

    debugFlags.set("H5header/header", true);
    debugFlags.set("H5header/btree2", true);
    debugFlags.set("H5header/continueMessage", true);
    debugFlags.set("H5header/headerDetails", true);
    debugFlags.set("H5header/dataBtree", true);
    debugFlags.set("H5header/groupBtree", true);
    debugFlags.set("H5header/Heap", true);
    debugFlags.set("H5header/filePos", true);
    debugFlags.set("H5header/reference", true);
    debugFlags.set("H5header/softLink", true);
    debugFlags.set("H5header/hardLink", true);
    debugFlags.set("H5header/symbolTable", true);
    debugFlags.set("H5header/memTracker", true);
    debugFlags.set("H5header/Variable", true);
    debugFlags.set("H5header/structure", true);

    H5iosp.setDebugFlags(debugFlags);
  }

  @Test
  public void issue273() throws IOException {
    File file = new File("/Users/cwardgar/Desktop/comp_complex_more.h5");
    Assert.assertTrue(doCompare(file.getAbsolutePath(), true, true, true));
  }


  private boolean doCompare(String location, boolean showCompare, boolean showEach, boolean compareData) throws IOException {
    try (NetcdfFile ncfile = NetcdfFile.open(location); NetcdfFile jni = openJni(location)) {
      jni.setLocation(location+" (jni)");
      //System.out.printf("Compare %s to %s%n", ncfile.getIosp().getClass().getName(), jni.getIosp().getClass().getName());

      Formatter f= new Formatter();
      CompareNetcdf2 tc = new CompareNetcdf2(f, showCompare, showEach, compareData);
      boolean ok = tc.compare(ncfile, jni, new CompareNetcdf2.Netcdf4ObjectFilter(), showCompare, showEach, compareData);
      System.out.printf(" %s compare %s ok = %s%n", ok ? "" : "***", location, ok);
      if (!ok ||(showCompare && showCompareResults)) System.out.printf("%s%n=====================================%n", f);

      System.out.println("\n\n\n\n");
      try (Writer writer = new BufferedWriter(new OutputStreamWriter(System.out, CDM.utf8Charset))) {
        NCdumpW.print(ncfile, writer, NCdumpW.WantValues.all, false, true, null, null);
        System.out.println("\n\n");
        NCdumpW.print(jni, writer, NCdumpW.WantValues.all, false, true, null, null);
      }

      return ok;
    }
  }

  private NetcdfFile openJni(String location) throws IOException {
    Nc4Iosp iosp = new Nc4Iosp(NetcdfFileWriter.Version.netcdf4);
    NetcdfFile ncfile = new NetcdfFileSubclass(iosp, location);
    RandomAccessFile raf = new RandomAccessFile(location, "r");
    iosp.open(raf, ncfile, null);
    return ncfile;
  }
}
