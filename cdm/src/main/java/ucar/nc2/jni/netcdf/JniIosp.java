/*
 * Copyright 1997-2008 Unidata Program Center/University Corporation for
 * Atmospheric Research, P.O. Box 3000, Boulder, CO 80307,
 * support@unidata.ucar.edu.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package ucar.nc2.jni.netcdf;

import ucar.nc2.iosp.AbstractIOServiceProvider;
import ucar.nc2.iosp.IOServiceProvider;
import ucar.nc2.iosp.IospHelper;
import ucar.nc2.*;
import ucar.nc2.util.CancelTask;
import ucar.unidata.io.RandomAccessFile;
import ucar.ma2.*;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.IntBuffer;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;

/**
 * IOSP for reading netcdf files through jni interface to netcdf4 library
 *
 * @author caron
 * @since Oct 30, 2008
 */
public class JniIosp extends AbstractIOServiceProvider {
  static private org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JniIosp.class);
  private static boolean debug = false, debugCompoundAtt= false, debugEnumAtt = false;

  public boolean isValidFile(RandomAccessFile raf) throws IOException {
    return false;
  }

  private NetcdfFile ncfile;
  private int ncid = -1, format;
  private boolean isClosed = false;

  public void open(RandomAccessFile raf, NetcdfFile ncfile, CancelTask cancelTask) throws IOException {
    load(); // load jni
    this.ncfile = ncfile;

    // open
    if (debug) System.out.println("open " + ncfile.getLocation());
    IntByReference ncidp = new IntByReference();
    int ret = nc4.nc_open(ncfile.getLocation(), 0, ncidp);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    ncid = ncidp.getValue();

    // format
    IntByReference formatp = new IntByReference();
    ret = nc4.nc_inq_format(ncid, formatp);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    format = formatp.getValue();
    System.out.printf("open %s id=%d format=%d %n", ncfile.getLocation(), ncid, format);

    // read root group
    makeGroup(ncid, new Group4(ncfile.getRootGroup(), null));

    ncfile.finish();
  }

  private void makeGroup(int grpid, Group4 g4) throws IOException {
    makeDimensions(grpid, g4);
    makeUserTypes(grpid, g4.g);

    // group attributes
    IntByReference ngattsp = new IntByReference();
    int ret = nc4.nc_inq_natts(grpid, ngattsp);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    List<Attribute> gatts = makeAttributes(grpid, NCLibrary.NC_GLOBAL, ngattsp.getValue());
    for (Attribute att : gatts) {
      ncfile.addAttribute(g4.g, att);
      if (debug) System.out.printf(" add Global Attribute %s %n", att);
    }

    makeVariables(grpid, g4.g);

    if (format == NCLibrary.NC_FORMAT_NETCDF4) {
      // read subordinate groups
      IntByReference numgrps = new IntByReference();
      ret = nc4.nc_inq_grps(grpid, numgrps, Pointer.NULL);
      if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
      int[] grids = new int[numgrps.getValue()];
      ret = nc4.nc_inq_grps(grpid, numgrps, grids);
      if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

      for (int i = 0; i < grids.length; i++) {
        byte[] name = new byte[NCLibrary.NC_MAX_NAME + 1];
        ret = nc4.nc_inq_grpname(grids[i], name);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        Group child = new Group(ncfile, g4.g, makeString(name));
        g4.g.addGroup(child);
        makeGroup(grids[i], new Group4(child, g4));
      }
    }

  }

  private void makeDimensions(int grpid, Group4 g4) throws IOException {
    IntByReference ndimsp = new IntByReference();
    int ret = nc4.nc_inq_ndims(grpid, ndimsp);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

    int[] dimids = new int[ndimsp.getValue()];
    ret = nc4.nc_inq_dimids(grpid, ndimsp, dimids, 0);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

    IntByReference nunlimdimsp = new IntByReference();
    int[] unlimdimids = new int[NCLibrary.NC_MAX_DIMS];
    ret = nc4.nc_inq_unlimdims(grpid, nunlimdimsp, unlimdimids);

    int ndims = ndimsp.getValue();
    for (int i = 0; i < ndims; i++) {
      byte[] name = new byte[NCLibrary.NC_MAX_NAME + 1];
      NativeLongByReference lenp = new NativeLongByReference();
      ret = nc4.nc_inq_dim(grpid, dimids[i], name, lenp);
      if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
      String dname = makeString(name);

      boolean isUnlimited = containsInt(nunlimdimsp.getValue(), unlimdimids, i);
      Dimension dim = new Dimension(dname, lenp.getValue().intValue(), true, isUnlimited, false);
      ncfile.addDimension(g4.g, dim);
      if (debug) System.out.printf(" add Dimension %s (%d) %n", dim, dimids[i]);
    }
  }

  private boolean containsInt(int n, int[] have, int want) {
    for (int i = 0; i < n; i++) {
      if (have[i] == want) return true;
    }
    return false;
  }

  private String makeString(byte[] b) throws IOException {
    // null terminates
    int count = 0;
    while (count < b.length) {
      if (b[count] == 0) break;
      count++; // dont include the terminating 0
    }

    // copy if its small
    if (count < b.length / 2) {
      byte[] bb = new byte[count];
      System.arraycopy(b, 0, bb, 0, count);
      b = bb;
    }

    return new String(b, 0, count, "UTF-8"); // all strings are considered to be UTF-8 unicode.
  }

  // follow what happpens in the Java side
  private String makeAttString(byte[] b) throws IOException {
    // null terminates
    int count = 0;
    while (count < b.length) {
      if (b[count] == 0) break;
      count++; // dont include the terminating 0
    }

    return new String(b, 0, count, "UTF-8"); // all strings are considered to be UTF-8 unicode.

   /*
    char[] carray = new char[count];
    for (int i=0; i<count; i++)
      carray[i] = (char) DataType.unsignedByteToShort(b[i]);

    return new String(carray); */
  }


  private List<Attribute> makeAttributes(int grpid, int varid, int natts) throws IOException {
    List<Attribute> result = new ArrayList<Attribute>(natts);

    for (int attnum = 0; attnum < natts; attnum++) {

      byte[] name = new byte[NCLibrary.NC_MAX_NAME + 1];
      int ret = nc4.nc_inq_attname(grpid, varid, attnum, name);
      if (ret != 0)
        throw new IOException(nc4.nc_strerror(ret) + " varid=" + varid + " attnum=" + attnum);
      String attname = makeString(name);

      IntByReference xtypep = new IntByReference();
      ret = nc4.nc_inq_atttype(grpid, varid, attname, xtypep);
      if (ret != 0)
        throw new IOException(nc4.nc_strerror(ret) + " varid=" + varid + "attnum=" + attnum);

      NativeLongByReference lenp = new NativeLongByReference();
      ret = nc4.nc_inq_attlen(grpid, varid, attname, lenp);
      if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
      int len = lenp.getValue().intValue();

      Array values = null;
      int type = xtypep.getValue();
      switch (type) {
        case NCLibrary.NC_UBYTE:
          byte[] valbu = new byte[len];
          ret = nc4.nc_get_att_uchar(grpid, varid, attname, valbu);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.BYTE.getPrimitiveClassType(), new int[]{len}, valbu);
          break;

        case NCLibrary.NC_BYTE:
          byte[] valb = new byte[len];
          ret = nc4.nc_get_att_schar(grpid, varid, attname, valb);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.BYTE.getPrimitiveClassType(), new int[]{len}, valb);
          break;

        case NCLibrary.NC_CHAR:
          byte[] text = new byte[len];
          ret = nc4.nc_get_att_text(grpid, varid, attname, text);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          Attribute att = new Attribute(attname, makeAttString(text));
          result.add(att);
          break;

        case NCLibrary.NC_DOUBLE:
          double[] vald = new double[len];
          ret = nc4.nc_get_att_double(grpid, varid, attname, vald);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.DOUBLE.getPrimitiveClassType(), new int[]{len}, vald);
          break;

        case NCLibrary.NC_FLOAT:
          float[] valf = new float[len];
          ret = nc4.nc_get_att_float(grpid, varid, attname, valf);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.FLOAT.getPrimitiveClassType(), new int[]{len}, valf);
          break;

        case NCLibrary.NC_UINT:
          int[] valiu = new int[len];
          ret = nc4.nc_get_att_uint(grpid, varid, attname, valiu);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.INT.getPrimitiveClassType(), new int[]{len}, valiu);
          break;

        case NCLibrary.NC_INT:
          int[] vali = new int[len];
          ret = nc4.nc_get_att_int(grpid, varid, attname, vali);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.INT.getPrimitiveClassType(), new int[]{len}, vali);
          break;

        case NCLibrary.NC_UINT64:
          long[] vallu = new long[len];
          ret = nc4.nc_get_att_ulonglong(grpid, varid, attname, vallu);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.LONG.getPrimitiveClassType(), new int[]{len}, vallu);
          break;

        case NCLibrary.NC_INT64:
          long[] vall = new long[len];
          ret = nc4.nc_get_att_longlong(grpid, varid, attname, vall);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.LONG.getPrimitiveClassType(), new int[]{len}, vall);
          break;

        case NCLibrary.NC_USHORT:
          short[] valsu = new short[len];
          ret = nc4.nc_get_att_ushort(grpid, varid, attname, valsu);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.SHORT.getPrimitiveClassType(), new int[]{len}, valsu);
          break;

        case NCLibrary.NC_SHORT:
          short[] vals = new short[len];
          ret = nc4.nc_get_att_short(grpid, varid, attname, vals);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(DataType.SHORT.getPrimitiveClassType(), new int[]{len}, vals);
          break;

        case NCLibrary.NC_STRING:
          String[] valss = new String[len];
          ret = nc4.nc_get_att_string(grpid, varid, attname, valss);
          if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
          values = Array.factory(String.class, new int[]{len}, valss);
          break;

        default:
          UserType userType = userTypes.get(type);
          if (userType == null) {
            log.warn("Unsupported attribute data type == " + type);
            continue;

          } else if (userType.userType == NCLibrary.NC_ENUM) {
            result.add( readEnumAttValues(grpid, varid, attname, len, userType));
            continue;

          } else if (userType.userType == NCLibrary.NC_OPAQUE) {
            result.add( readOpaqueAttValues(grpid, varid, attname, len, userType));
            continue;

          } else if (userType.userType == NCLibrary.NC_VLEN) {
            NCLibrary.Vlen_t[] vlen = new NCLibrary.Vlen_t[len];
            ret = nc4.nc_get_att(grpid, varid, attname, vlen);    // vlen
            if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
            
            Array data = extractVlen( userType, len, vlen);
            result.add( new Attribute(attname, data));
            continue;

          } else if (userType.userType == NCLibrary.NC_COMPOUND) {

            if (userType.flds == null)
              userType.readFields();

            int buffSize = 1000; // LOOK
            ByteBuffer bbuff = ByteBuffer.allocate(buffSize);
            ret = nc4.nc_get_att(grpid, varid, attname, bbuff);
            if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
            bbuff.order(ByteOrder.LITTLE_ENDIAN);

            for (Field fld : userType.flds) {
              fld.data = Array.factory(DataType.FLOAT, new int[] { len});
            }

            for (int i=0; i<len; i++) {
              int record_start = i * userType.size;

              for (Field fld : userType.flds) {
                int pos = record_start + fld.offset;

                switch (fld.fldtypeid) {
                  case NCLibrary.NC_BYTE:
                    byte bval = bbuff.get(pos);
                    if (debugCompoundAtt) System.out.println("bval= "+bval);
                    fld.data.setByte(i, bval);
                    continue;
                  case NCLibrary.NC_SHORT:
                    short sval = bbuff.getShort(pos);
                    if (debugCompoundAtt) System.out.println("sval= "+sval);
                    fld.data.setShort(i, sval);
                    continue;
                  case NCLibrary.NC_INT:
                    int ival = bbuff.getInt(pos);
                    if (debugCompoundAtt) System.out.println("ival= "+ival);
                    fld.data.setInt(i, ival);
                    continue;
                  case NCLibrary.NC_INT64:
                    long lval = bbuff.getLong(pos);
                    if (debugCompoundAtt) System.out.println("lval= "+lval);
                    fld.data.setLong(i, lval);
                    continue;
                  case NCLibrary.NC_FLOAT:
                    float fval = bbuff.getFloat(pos);
                    if (debugCompoundAtt) System.out.println("fval= "+fval);
                    fld.data.setFloat(i, fval);
                    continue;
                  case NCLibrary.NC_DOUBLE:
                    double dval = bbuff.getDouble(pos);
                    if (debugCompoundAtt) System.out.println("dval= "+dval);
                    fld.data.setDouble(i, dval);
                    continue;
                  default:
                    System.out.println("counpounf attribute fld.fldtypeid= "+fld.fldtypeid);
                    continue;
                }
              }

            }

            for (Field fld : userType.flds) {
              result.add( new Attribute(attname+"."+fld.name,  fld.data));
            }

            continue;
          }

          log.warn("Unsupported attribute data type == " + userType);
          continue;
      }

      if (values != null) {
        Attribute att = new Attribute(attname, values);
        result.add(att);
      }
    }

    return result;
  }

  Array extractVlen(UserType userType, int len, NCLibrary.Vlen_t[] vlen) {
    switch (userType.baseTypeid) {
      case NCLibrary.NC_INT:
        int count = 0;
        for (int i = 0; i < len; i++)
          count += vlen[i].len;

        Array intArray = Array.factory(DataType.INT, new int[]{count});
        IndexIterator iter = intArray.getIndexIterator();
        for (int i = 0; i < len; i++) {
          System.out.print(" len=" + vlen[i].len + "; p= " + vlen[i].p + ";");
          int[] ba = vlen[i].p.getIntArray(0, vlen[i].len);
          for (int j = 0; j < ba.length; j++) {
            System.out.print(" " + ba[j]);
            iter.setIntNext(ba[j]);
          }
          System.out.println();
        }
        return intArray;
    }
    return null;
  }

  private Attribute readEnumAttValues(int grpid, int varid, String attname, int len, UserType userType) throws IOException {
    int ret;

    DataType dtype = convertDataType(userType.baseTypeid);
    int elemSize = dtype.getSize();

    ByteBuffer bb = ByteBuffer.allocate(len * elemSize);
    ret = nc4.nc_get_att(grpid, varid, attname, bb);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

    Array data = convertByteBuffer(bb, userType.baseTypeid, new int[] {len});
    IndexIterator ii = data.getIndexIterator();

    if (len == 1) {
      String val = userType.e.lookupEnumString( ii.getIntNext());
      return new Attribute(attname, val);

    } else {
      ArrayObject.D1 attArray = (ArrayObject.D1) Array.factory(DataType.STRING, new int[]{len});
      for (int i = 0; i < len; i++) {
        int val = ii.getIntNext();
        String vals = userType.e.lookupEnumString( val);
        if (vals == null)
          throw new IOException("Illegal enum val " +val + " for attribute " + attname);
        attArray.set(i, vals);
      }
      return new Attribute(attname, attArray);
    }

  }

  private Attribute readOpaqueAttValues(int grpid, int varid, String attname, int len, UserType userType) throws IOException {
    int total = len * userType.size;
    ByteBuffer bb = ByteBuffer.allocate(total);
    int ret = nc4.nc_get_att(grpid, varid, attname, bb);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    return new Attribute(attname, Array.factory(DataType.BYTE, new int[] {total}, bb.array()));
  }


  /////////////////////////////////////////////////////////////////////////////

  private void makeVariables(int grpid, Group g) throws IOException {
    IntByReference nvarsp = new IntByReference();
    int ret = nc4.nc_inq_nvars(grpid, nvarsp);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    int nvars = nvarsp.getValue();
    if (debug) System.out.printf(" nvars= %d %n", nvars);

    int[] varids = new int[nvars];
    ret = nc4.nc_inq_varids(grpid, nvarsp, varids);

    for (int i = 0; i < varids.length; i++) {
      int varno = varids[i];
      if (varno != i) System.out.printf("HEY varno=%d i=%d%n", varno, i);

      byte[] name = new byte[NCLibrary.NC_MAX_NAME + 1];
      IntByReference xtypep = new IntByReference();
      IntByReference ndimsp = new IntByReference();
      int[] dimids = new int[NCLibrary.NC_MAX_DIMS];
      IntByReference nattsp = new IntByReference();

      ret = nc4.nc_inq_var(grpid, varno, name, xtypep, ndimsp, dimids, nattsp);
      if (ret != 0)
        throw new IOException(nc4.nc_strerror(ret));

      // figure out the datatype
      int typeid = xtypep.getValue();
      DataType dtype = convertDataType(typeid);

      String vname = makeString(name);
      Vinfo vinfo = new Vinfo(grpid, varno, typeid);

      // figure out the dimensions
      String dimList = makeDimList(grpid, ndimsp.getValue(), dimids);
      UserType utype = userTypes.get(typeid);
      if (utype != null) {
        vinfo.utype = utype;
        if (utype.userType == NCLibrary.NC_VLEN)
          dimList = dimList +" *";
      }

      Variable v;
      if (dtype != DataType.STRUCTURE) {
        v = new Variable(ncfile, g, null, vname, dtype, dimList);

      } else {
        Structure s = new Structure(ncfile, g, null, vname);
        s.setDimensions( dimList);
        v = s;

        if (utype.flds == null)
          utype.readFields();
        for (Field f : utype.flds) {
          s.addMemberVariable(f.makeMemberVariable(g, s));
        }
      }

      // create the Variable
      ncfile.addVariable(g, v);
      v.setSPobject(vinfo);
      if (dtype.isEnum()) {
        EnumTypedef enumTypedef = g.findEnumeration(utype.name);
        v.setEnumTypedef(enumTypedef);
      }

      // read Variable attributes
      List<Attribute> atts = makeAttributes(grpid, varno, nattsp.getValue());
      for (Attribute att : atts) {
        v.addAttribute(att);
        //if (debug) System.out.printf(" add Variable Attribute %s %n",att);
      }

      if (debug) System.out.printf(" add Variable %s %n", v);
    }
  }

  private String makeDimList(int grpid, int ndimsp, int[] dims) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < ndimsp; i++) {
      byte[] name = new byte[NCLibrary.NC_MAX_NAME + 1];
      int ret = nc4.nc_inq_dimname(grpid, dims[i], name);
      if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
      String dname = makeString(name);
      sb.append(dname);
      sb.append(" ");
    }
    return sb.toString();
  }

  private class Vinfo {
    int grpid, varid, typeid;
    UserType utype; // may be null

    Vinfo(int grpid, int varid, int typeid) {
      this.grpid = grpid;
      this.varid = varid;
      this.typeid = typeid;
    }
  }

  //////////////////////////////////////////////////////////////////////////
  private class Group4 {
    Group g;
    Group4 parent;
    int[] dimids;
    Group4( Group g, Group4 parent) {
      this.g = g;
      this.parent = parent;
    }
  }

  private class UserType {
    int grpid;
    int typeid;
    String name;
    int size; // the size of the user defined type
    int baseTypeid; // the base typeid for vlen and enum types
    long nfields; // the number of fields for enum and compound types
    int userType; // the class of the user defined type: NC_VLEN, NC_OPAQUE, NC_ENUM, or NC_COMPOUND.

    EnumTypedef e;
    List<Field> flds;

    UserType(int grpid, int typeid, String name, long size, int baseTypeid, long nfields, int userType) {
      this.grpid = grpid;
      this.typeid = typeid;
      this.name = name;
      this.size = (int) size;
      this.baseTypeid = baseTypeid;
      this.nfields = nfields;
      this.userType = userType;
    }

    void setEnum(EnumTypedef e) {
      this.e = e;
    }

    void addField(Field fld) {
      if (flds == null)
        flds = new ArrayList<Field>(10);
      flds.add(fld);
    }

    public String toString() {
      return "name='"+name+"' id="+getDataTypeName(typeid)+" userType="+getDataTypeName(userType)+
              " baseType="+getDataTypeName(baseTypeid);
    }

    void readFields() throws IOException {
      for (int fldidx=0; fldidx<nfields; fldidx++) {
        byte[] fldname = new byte[NCLibrary.NC_MAX_NAME + 1];
        IntByReference field_typeidp = new IntByReference();
        IntByReference ndimsp = new IntByReference();
        IntByReference dim_sizesp = new IntByReference();
        NativeLongByReference offsetp = new NativeLongByReference();

        int[] dims = new int[NCLibrary.NC_MAX_DIMS];
        int ret = nc4.nc_inq_compound_field(grpid, typeid, fldidx, fldname, offsetp, field_typeidp, ndimsp, dims);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

        Field fld = new Field(grpid, typeid, fldidx, makeString(fldname), offsetp.getValue().intValue(),
                field_typeidp.getValue(), ndimsp.getValue(), dims);

        addField( fld);
        System.out.println(" add field= "+fld);
      }
    }
  }

  private class Field {
    int grpid;
    int typeid;
    int fldidx;
    String name;
    int offset;
    int fldtypeid;
    int ndims;
    int[] dims;

    DataType dtype;
    int total_size;
    Array data;

    // grpid, varid, fldidx, fldname, offsetp, field_typeidp, ndimsp, dim_sizesp
    Field(int grpid, int typeid, int fldidx, String name, int offset, int fldtypeid, int ndims, int[] dims) {
      this.grpid = grpid;
      this.typeid = typeid;
      this.fldidx = fldidx;
      this.name = name;
      this.offset = offset;
      this.fldtypeid = fldtypeid;
      this.ndims = ndims;
      this.dims = new int[ndims];
      System.arraycopy(dims, 0, this.dims, 0, ndims);

      dtype = convertDataType(fldtypeid);
      try {
        Section s = new Section( dims);
        total_size = (int) s.computeSize() * dtype.getSize();
      } catch (InvalidRangeException e) {
        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
      }
    }

    public String toString() {
      return "name='"+name+" fldtypeid="+getDataTypeName(fldtypeid)+" ndims="+ndims+" offset="+offset;
    }

    Variable makeMemberVariable(Group g, Structure parent) {
      Variable v = new Variable(ncfile, g, parent, name);
      v.setDataType( convertDataType(fldtypeid));
      try {
        v.setDimensionsAnonymous(dims);
      } catch (InvalidRangeException e) {
        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
      }
      return v;
    }

  }

  private Map<Integer, UserType> userTypes = new HashMap<Integer, UserType>();

  private void makeUserTypes(int grpid, Group g) throws IOException {
    // find user types in this group
    IntByReference ntypesp = new IntByReference();
    int ret = nc4.nc_inq_typeids(grpid, ntypesp, Pointer.NULL);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    int ntypes = ntypesp.getValue();
    if (ntypes == 0) return;
    int[] xtypes = new int[ntypes];
    ret = nc4.nc_inq_typeids(grpid, ntypesp, xtypes);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

    // for each defined "user type", get information, store in Map
    for (int typeid : xtypes) {
      byte[] nameb = new byte[NCLibrary.NC_MAX_NAME + 1];
      NativeLongByReference sizep = new NativeLongByReference();
      IntByReference baseType = new IntByReference();
      NativeLongByReference nfieldsp = new NativeLongByReference();
      IntByReference classp = new IntByReference();

      ret = nc4.nc_inq_user_type(grpid, typeid, nameb, sizep, baseType, nfieldsp, classp); // size_t
      if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

      String name = makeString(nameb);
      int utype = classp.getValue();
      System.out.printf(" user type=%d name=%s size=%d baseType=%d nfields=%d class=%d %n ",
          typeid, name, sizep.getValue().longValue(), baseType.getValue(), nfieldsp.getValue().longValue(), utype);

      UserType ut = new UserType(grpid, typeid, name, sizep.getValue().longValue(), baseType.getValue(),
          nfieldsp.getValue().longValue(), classp.getValue());
      userTypes.put(typeid, ut);

      if (utype == NCLibrary.NC_ENUM) {
        Map<Integer, String> map = makeEnum(grpid, typeid);
        EnumTypedef e = new EnumTypedef(name, map);
        g.addEnumeration(e);
        ut.setEnum(e);

     } else if (utype == NCLibrary.NC_OPAQUE) {
        byte[] nameo = new byte[NCLibrary.NC_MAX_NAME + 1];
        NativeLongByReference sizep2 = new NativeLongByReference();
        ret = nc4.nc_inq_opaque(grpid, typeid, nameo, sizep2);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        String nameos = makeString(nameo);

        // doesnt seem to be any new info
        System.out.printf("   opaque type=%d name=%s size=%d %n ",
            typeid, nameos, sizep2.getValue().longValue());
      }
    }
  }

  private Map<Integer, String> makeEnum(int grpid, int xtype) throws IOException {
    byte[] nameb = new byte[NCLibrary.NC_MAX_NAME + 1];
    IntByReference baseType = new IntByReference();
    NativeLongByReference baseSize = new NativeLongByReference();
    NativeLongByReference numMembers = new NativeLongByReference();

    int ret = nc4.nc_inq_enum(grpid, xtype, nameb, baseType, baseSize, numMembers);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    int nmembers = numMembers.getValue().intValue();
    String name = makeString(nameb);

    //System.out.printf(" type=%d name=%s baseType=%d baseType=%d numMembers=%d %n ",
    //    xtype, name, baseType.getValue(), baseSize.getValue().longValue(), nmembers);
    Map<Integer, String> map = new HashMap<Integer, String>(2 * nmembers);

    for (int i = 0; i < nmembers; i++) {
      byte[] mnameb = new byte[NCLibrary.NC_MAX_NAME + 1];
      IntByReference value = new IntByReference();
      ret = nc4.nc_inq_enum_member(grpid, xtype, i, mnameb, value); // void *
      if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

      String mname = makeString(mnameb);
      //System.out.printf(" member name=%s value=%d %n ",  mname, value.getValue());
      map.put(value.getValue(), mname);
    }

    return map;
  }

  /////////////////////////////////////////////////////////////////////////////////

  public Array readData(Variable v2, Section section) throws IOException, InvalidRangeException {
    Vinfo vinfo = (Vinfo) v2.getSPobject();
    Array values = null;
    int vlen = (int) v2.getSize();
    int len = (int) section.computeSize();
    if (vlen == len) return read(v2); // entire array

    switch (v2.getDataType()) {
      case BYTE:
        byte[] valb = new byte[len];
        // int nc_get_vars_schar(int ncid, int varid, int[] startp, int[] countp, int[] stridep, byte[] ip); // size_t, ptrdiff_t
        int ret = nc4.nc_get_vars_schar(vinfo.grpid, vinfo.varid,
            convert(section.getOrigin()), convert(section.getShape()), section.getStride(), valb);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        values = Array.factory(DataType.BYTE.getPrimitiveClassType(), section.getShape(), valb);
        break;

      case CHAR:
        byte[] valc = new byte[len];
        ret = nc4.nc_get_vars_text(vinfo.grpid, vinfo.varid,
            convert(section.getOrigin()), convert(section.getShape()), section.getStride(), valc);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        values = Array.factory(DataType.CHAR.getPrimitiveClassType(), section.getShape(), IospHelper.convertByteToChar(valc));
        break;

      case DOUBLE:
        double[] vald = new double[len];
        ret = nc4.nc_get_vars_double(vinfo.grpid, vinfo.varid,
            convert(section.getOrigin()), convert(section.getShape()), section.getStride(), vald);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        values = Array.factory(DataType.DOUBLE.getPrimitiveClassType(), section.getShape(), vald);
        break;

      case FLOAT:
        float[] valf = new float[len];
        ret = nc4.nc_get_vars_float(vinfo.grpid, vinfo.varid,
            convert(section.getOrigin()), convert(section.getShape()), section.getStride(), valf);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        values = Array.factory(DataType.FLOAT.getPrimitiveClassType(), section.getShape(), valf);
        break;

      case INT:
        int[] vali = new int[len];
        ret = nc4.nc_get_vars_int(vinfo.grpid, vinfo.varid,
            convert(section.getOrigin()), convert(section.getShape()), section.getStride(), vali);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        values = Array.factory(DataType.INT.getPrimitiveClassType(), section.getShape(), vali);
        break;

      case LONG:
        long[] vall = new long[len];
        ret = nc4.nc_get_vars_longlong(vinfo.grpid, vinfo.varid,
            convert(section.getOrigin()), convert(section.getShape()), section.getStride(), vall);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        values = Array.factory(DataType.LONG.getPrimitiveClassType(), section.getShape(), vall);
        break;

      case SHORT:
        short[] vals = new short[len];
        ret = nc4.nc_get_vars_short(vinfo.grpid, vinfo.varid,
            convert(section.getOrigin()), convert(section.getShape()), section.getStride(), vals);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        values = Array.factory(DataType.SHORT.getPrimitiveClassType(), section.getShape(), vals);
        break;

      default:
        throw new IOException("Unsupported data type = " + v2.getDataType());
    }

    return values;
  }

  private long[] convert(int[] from) {
    long[] to = new long[from.length];
    for (int i = 0; i < from.length; i++)
      to[i] = from[i];
    return to;
  }

  private Array read(Variable v2) throws IOException, InvalidRangeException {
    Vinfo vinfo = (Vinfo) v2.getSPobject();
    Array values = null;
    int len = (int) v2.getSize();

    switch (v2.getDataType()) {
      case ENUM1:
      case BYTE:
        byte[] valb = (byte[]) read(vinfo.grpid, vinfo.varid, vinfo.typeid, len);
        values = Array.factory( DataType.BYTE.getPrimitiveClassType(), v2.getShape(), valb);
        break;

      case CHAR:
        char[] valc = (char[] )read(vinfo.grpid, vinfo.varid, vinfo.typeid, len);
        values = Array.factory(DataType.CHAR.getPrimitiveClassType(), v2.getShape(), valc);
        break;

      case DOUBLE:
        double[] vald = (double[] )read(vinfo.grpid, vinfo.varid, vinfo.typeid, len);
        values = Array.factory(DataType.DOUBLE.getPrimitiveClassType(), v2.getShape(), vald);
        break;

      case FLOAT:
        float[] valf = (float[] )read(vinfo.grpid, vinfo.varid, vinfo.typeid, len);
        values = Array.factory(DataType.FLOAT.getPrimitiveClassType(), v2.getShape(), valf);
        break;

      case ENUM4:
      case INT:
        int[] vali = (int[] )read(vinfo.grpid, vinfo.varid, vinfo.typeid, len);
        values = Array.factory(DataType.INT.getPrimitiveClassType(), v2.getShape(), vali);
        break;

      case LONG:
        long[] vall = (long[] )read(vinfo.grpid, vinfo.varid, vinfo.typeid, len);
        values = Array.factory(DataType.LONG.getPrimitiveClassType(), v2.getShape(), vall);
        break;

      case ENUM2:
      case SHORT:
        short[] vals = (short[] )read(vinfo.grpid, vinfo.varid, vinfo.typeid, len);
        values = Array.factory(DataType.SHORT.getPrimitiveClassType(), v2.getShape(), vals);
        break;

      case STRING:
        String[] valss = (String[] )read(vinfo.grpid, vinfo.varid, vinfo.typeid, len);
        values = Array.factory(DataType.STRING.getPrimitiveClassType(), v2.getShape(), valss);
        break;

      case OPAQUE:
        UserType userType = userTypes.get( vinfo.typeid);
        values = readOpaque(vinfo.grpid, vinfo.varid, len, userType.size);
        break;

      default:
         throw new IOException("Unsupported data type = " + v2.getDataType()+ " utype= "+vinfo.utype);
    }

    return values;
  }

  private Object read(int grpid, int varid, int typeid, int len) throws IOException, InvalidRangeException {
    int ret;

    switch (typeid) {
      case NCLibrary.NC_OPAQUE:
        ByteBuffer bb = ByteBuffer.allocate(len);
        ret = nc4.nc_get_var(grpid, varid, bb);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return bb;

      case NCLibrary.NC_UBYTE:
        byte[] valbu = new byte[len];
        ret = nc4.nc_get_var_ubyte(grpid, varid, valbu);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return valbu;

      case NCLibrary.NC_BYTE:
        byte[] valb = new byte[len];
        ret = nc4.nc_get_var_schar(grpid, varid, valb);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return valb;

      case NCLibrary.NC_CHAR:
        byte[] valc = new byte[len];
        ret = nc4.nc_get_var_text(grpid, varid, valc);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return IospHelper.convertByteToChar(valc);

      case NCLibrary.NC_DOUBLE:
        double[] vald = new double[len];
        ret = nc4.nc_get_var_double(grpid, varid, vald);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return vald;

      case NCLibrary.NC_FLOAT:
        float[] valf = new float[len];
        ret = nc4.nc_get_var_float(grpid, varid, valf);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return valf;

      case NCLibrary.NC_INT:
        int[] vali = new int[len];
        ret = nc4.nc_get_var_int(grpid, varid, vali);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return vali;

      case NCLibrary.NC_INT64:
        long[] vall = new long[len];
        ret = nc4.nc_get_var_longlong(grpid, varid, vall);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return vall;

      case NCLibrary.NC_UINT64:
        long[] vallu = new long[len];
        ret = nc4.nc_get_var_ulonglong(grpid, varid, vallu);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return vallu;

      case NCLibrary.NC_SHORT:
        short[] vals = new short[len];
        ret = nc4.nc_get_var_short(grpid, varid, vals);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return vals;

      case NCLibrary.NC_USHORT:
        short[] valsu = new short[len];
        ret = nc4.nc_get_var_ushort(grpid, varid, valsu);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return valsu;

      case NCLibrary.NC_STRING:
        String[] valss = new String[len];
        ret = nc4.nc_get_var_string(grpid, varid, valss);
        if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
        return valss;

      default:
        UserType userType = userTypes.get(typeid);
        if (userType == null) {
          throw new IOException("Unsupported data type == " + typeid);

        } else if (userType.userType == NCLibrary.NC_ENUM) {
          return readEnum(grpid, varid, userType.baseTypeid, len);

        } else if (userType.userType == NCLibrary.NC_OPAQUE) {
          byte[] ball = (byte []) read(grpid, varid, userType.userType, (int) (len * userType.size));
          int inner = (int) userType.size;
          int outer = len;
          byte[][] result = new byte[outer][];
          int pos = 0;
          for (int i = 0; i < outer; i++) {
            result[i] = new byte[inner];
            System.arraycopy(ball, pos, result[i], 0, inner);
            pos += inner;
          }
          return result;

        } else if (userType.userType == NCLibrary.NC_VLEN) {
          return read(grpid, varid, userType.userType, (int) (len * userType.size));

         } else  {
          return read(grpid, varid, userType.userType, len);
        }
    }
  }

  // opaques use ArrayObjects of ByteBuffer
  private Array readOpaque(int grpid, int varid, int len, int size) throws IOException, InvalidRangeException {
    int ret;

    ByteBuffer bb = ByteBuffer.allocate(len*size);
    ret = nc4.nc_get_var(grpid, varid, bb);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    byte[] entire = bb.array();

    ArrayObject values = new ArrayObject(ByteBuffer.class, new int[]{len});
    int count = 0;
    IndexIterator ii = values.getIndexIterator();
    while (ii.hasNext()) {
      ii.setObjectNext( ByteBuffer.wrap(entire, count*size, size));
    }

    return values;
  }

  private Object readEnum(int grpid, int varid, int baseType, int len) throws IOException, InvalidRangeException {
    int ret;

    DataType dtype = convertDataType(baseType);
    int elemSize = dtype.getSize();

    ByteBuffer bb = ByteBuffer.allocate(len * elemSize);
    ret = nc4.nc_get_var(grpid, varid, bb);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));

    switch (baseType) {
      case NCLibrary.NC_BYTE:
      case NCLibrary.NC_UBYTE:
        return bb.array();

      case NCLibrary.NC_SHORT:
      case NCLibrary.NC_USHORT:
        ShortBuffer sb = bb.asShortBuffer();
        return sb.array();

      case NCLibrary.NC_INT:
      case NCLibrary.NC_UINT:
        IntBuffer ib = bb.asIntBuffer();
        return ib.array();
    }

    return null;
  }

  private Array convertByteBuffer(ByteBuffer bb, int baseType, int shape[]) throws IOException {

    switch (baseType) {
      case NCLibrary.NC_BYTE:
      case NCLibrary.NC_UBYTE:
        Array sArray = Array.factory(DataType.BYTE, shape, bb.array());
        return (baseType == NCLibrary.NC_BYTE) ? sArray : MAMath.convertUnsigned(sArray);

      case NCLibrary.NC_SHORT:
      case NCLibrary.NC_USHORT:
        ShortBuffer sb = bb.asShortBuffer();
        sArray = Array.factory(DataType.SHORT, shape, sb.array());
        return (baseType == NCLibrary.NC_SHORT) ? sArray : MAMath.convertUnsigned(sArray);


      case NCLibrary.NC_INT:
      case NCLibrary.NC_UINT:
        IntBuffer ib = bb.asIntBuffer();
        sArray = Array.factory(DataType.INT, shape, ib.array());
        return (baseType == NCLibrary.NC_INT) ? sArray : MAMath.convertUnsigned(sArray);
    }

    return null;
  }


  public void close() throws IOException {
    if (isClosed) return;
    if (ncid < 0) return;
    int ret = nc4.nc_close(ncid);
    if (ret != 0) throw new IOException(nc4.nc_strerror(ret));
    isClosed = true;
  }

  private DataType convertDataType(int type) {
    switch (type) {
      case NCLibrary.NC_BYTE:
      case NCLibrary.NC_UBYTE:
        return DataType.BYTE;

      case NCLibrary.NC_CHAR:
        return DataType.CHAR;

      case NCLibrary.NC_SHORT:
      case NCLibrary.NC_USHORT:
        return DataType.SHORT;

      case NCLibrary.NC_INT:
      case NCLibrary.NC_UINT:
        return DataType.INT;

      case NCLibrary.NC_INT64:
      case NCLibrary.NC_UINT64:
        return DataType.LONG;

      case NCLibrary.NC_FLOAT:
        return DataType.FLOAT;

      case NCLibrary.NC_DOUBLE:
        return DataType.DOUBLE;

      case NCLibrary.NC_ENUM:
        return DataType.ENUM1;

      case NCLibrary.NC_STRING:
        return DataType.STRING;

      default:
        UserType userType = userTypes.get(type);
        if (userType == null)
          throw new IllegalArgumentException("unknown type == " + type);

        switch (userType.userType) {
          case NCLibrary.NC_ENUM:
            return DataType.ENUM1;

          case NCLibrary.NC_COMPOUND:
            return DataType.STRUCTURE;

          case NCLibrary.NC_OPAQUE:
            return DataType.OPAQUE;

          case NCLibrary.NC_VLEN:
            return convertDataType(userType.baseTypeid);
        }
        throw new IllegalArgumentException("unknown type == " + type);
    }
  }

  private String getDataTypeName(int type) {
    switch (type) {
      case NCLibrary.NC_BYTE: return "byte";
      case NCLibrary.NC_UBYTE: return "ubyte";
      case NCLibrary.NC_CHAR: return "char";
      case NCLibrary.NC_SHORT: return "short";
      case NCLibrary.NC_USHORT: return "ushort";
      case NCLibrary.NC_INT: return "int";
      case NCLibrary.NC_UINT: return "uint";
      case NCLibrary.NC_INT64: return "long";
      case NCLibrary.NC_UINT64: return "ulong";
      case NCLibrary.NC_FLOAT: return "float";
      case NCLibrary.NC_DOUBLE: return "double";
      case NCLibrary.NC_ENUM: return "enum";
      case NCLibrary.NC_STRING: return "string";
      case NCLibrary.NC_COMPOUND: return "struct";
      case NCLibrary.NC_OPAQUE: return "opaque";
      case NCLibrary.NC_VLEN: return "vlen";

      default:
        UserType userType = userTypes.get(type);
        if (userType == null)
          return "unknown type " + type;

        switch (userType.userType) {
          case NCLibrary.NC_ENUM: return "userType-enum";
          case NCLibrary.NC_COMPOUND: return "userType-struct";
          case NCLibrary.NC_OPAQUE: return "userType-opaque";
          case NCLibrary.NC_VLEN: return "userType-vlen";
        }
        return "unknown userType " + userType.userType;
    }
  }


  private static NCLibrary nc4;

  private NCLibrary load() {
    if (nc4 == null) {
      //String dir = "C:/cdev/libpath/";
      String dir = "C:/dev/tds/thredds/lib/binary/win32/";
      System.setProperty("jna.library.path", dir);

      System.load(dir + "zlib1.dll");
      System.load(dir + "szlibdll.dll");
      System.load(dir + "hdf5dll.dll");
      System.load(dir + "hdf5_hldll.dll");
      //System.load(dir + "netcdf.dll");

      Native.setProtected(true);
      nc4 = (NCLibrary) Native.loadLibrary("netcdf", NCLibrary.class);
      System.out.printf(" Netcdf nc_inq_libvers=%s isProtecetd=%s %n ", nc4.nc_inq_libvers(), Native.isProtected());
    }

    return nc4;
  }

  private static class MyNetcdfFile extends NetcdfFile {
    MyNetcdfFile(IOServiceProvider spi) {
      this.spi = spi;
    }
  }

  public NetcdfFile open(String location) throws Exception {
    MyNetcdfFile ncfile = new MyNetcdfFile(this);
    ncfile.setLocation(location);
    try {
      open(null, ncfile, null);
    } catch (Exception e) {
      close(); // make sure that the file gets closed
      throw e;
    }
    return ncfile;
  }

  public static void main(String args[]) throws Exception {
    JniIosp iosp = new JniIosp();
    NetcdfFile ncfile = iosp.open("C:/data/test2.nc");
    System.out.println("" + ncfile);
  }

}
