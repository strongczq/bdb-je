/*-
 *
 *  This file is part of Oracle Berkeley DB Java Edition
 *  Copyright (C) 2002, 2015 Oracle and/or its affiliates.  All rights reserved.
 *
 *  Oracle Berkeley DB Java Edition is free software: you can redistribute it
 *  and/or modify it under the terms of the GNU Affero General Public License
 *  as published by the Free Software Foundation, version 3.
 *
 *  Oracle Berkeley DB Java Edition is distributed in the hope that it will be
 *  useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License in
 *  the LICENSE file along with Oracle Berkeley DB Java Edition.  If not, see
 *  <http://www.gnu.org/licenses/>.
 *
 *  An active Oracle commercial licensing agreement for this product
 *  supercedes this license.
 *
 *  For more information please contact:
 *
 *  Vice President Legal, Development
 *  Oracle America, Inc.
 *  5OP-10
 *  500 Oracle Parkway
 *  Redwood Shores, CA 94065
 *
 *  or
 *
 *  berkeleydb-info_us@oracle.com
 *
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  [This line intentionally left blank.]
 *  EOF
 *
 */

package com.sleepycat.utilint;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.io.IOException;

import org.junit.Test;

import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;

public class StatLoggerTest extends TestBase {

    String filename = "testStatFile";

    @Test
    public void testBasic() throws IOException{
        StringBuffer rowbuf = new StringBuffer();
        String fileext = "csv";
        int columnCount = 4;
        String columnDelimiter = ",";
        int rowsInFile = 4;
        int numFiles = 3;
        FileFilter ff = new FindFile(filename);
        String header;

        File envHome = SharedTestUtils.getTestDir();
        File[] files = envHome.listFiles(ff);
        for (File f : files) {
            f.delete();
        }

        StatLogger sl = new StatLogger(
            envHome, filename, fileext, numFiles, rowsInFile);

        rowbuf.setLength(0);
        for (int i = 0; i < columnCount; i++) {
            if (i > 0) {
                rowbuf.append(columnDelimiter);
            }
            rowbuf.append("Column Header" + i);
        }
        header = rowbuf.toString();
        sl.setHeader(header);
        rowbuf.setLength(0);
        for (int i = 0; i < columnCount; i++) {
            if (i > 0) {
                rowbuf.append(columnDelimiter);
            }
            rowbuf.append(i);
        }

        for (int i = 0; i < rowsInFile - 1; i++) {
            sl.log(rowbuf.toString());
        }

        files = envHome.listFiles(ff);
        assertEquals(files.length, 1);

        for (int j = 0; j < numFiles - 1; j++) {
            for (int i = 0; i < rowsInFile - 1; i++) {
                sl.log(rowbuf.toString());
            }
        }
        files = envHome.listFiles(ff);
        assertEquals(files.length, numFiles);

        /* add more rows but file number should be max */
        for (int j = 0; j < numFiles; j++) {
            for (int i = 0; i < rowsInFile; i++) {
                sl.log(rowbuf.toString());
            }
        }

        sl.log(rowbuf.toString());
        files = envHome.listFiles(ff);
        assertEquals(files.length, numFiles);

        /* Recreate logger like a reboot with existing
         * stat file. Make sure file count is correct.
         */
        files = envHome.listFiles(ff);
        for (File f : files) {
            f.delete();
        }

        sl = new StatLogger(
            envHome, filename, fileext, numFiles, rowsInFile);
        sl.setHeader(header);

        for (int i = 0; i < rowsInFile - 1; i++) {
            sl.log(rowbuf.toString());
        }
        files = envHome.listFiles(ff);
        assertEquals(files.length, 1);

        sl = new StatLogger(
            envHome, filename, fileext, numFiles, rowsInFile);
        sl.setHeader(header);
        files = envHome.listFiles(ff);
        assertEquals(files.length, 1);

        sl.log(rowbuf.toString());
        files = envHome.listFiles(ff);
        assertEquals(files.length, 2);

        /* Test changing the row count */
        sl.setRowCount(10);
        for (int i = 0; i < 5; i++) {
            sl.log(rowbuf.toString());
        }
        files = envHome.listFiles(ff);
        assertEquals(files.length, 2);
    }

    @Test
    public void testDelta() throws IOException {
        StringBuffer rowbuf = new StringBuffer();
        String fileext = "csv";
        int columnCount = 4;
        String columnDelimiter = ",";
        int rowsInFile = 100;
        int numFiles = 3;
        FileFilter ff = new FindFile(filename);
        String header;
        File envHome = SharedTestUtils.getTestDir();
        File testfile =
            new File(envHome.getAbsolutePath() + File.separator + filename +
                        "." + fileext);

        File[] files = envHome.listFiles(ff);
        for (File f : files) {
            f.delete();
        }

        StatLogger sl = new StatLogger(
            envHome, filename, fileext, numFiles, rowsInFile);

        rowbuf.setLength(0);
        for (int i = 0; i < columnCount; i++) {
            if (i > 0) {
                rowbuf.append(columnDelimiter);
            }
            rowbuf.append("Column Header" + i);
        }
        header = rowbuf.toString();
        rowbuf.setLength(0);
        for (int i = 0; i < columnCount; i++) {
            if (i > 0) {
                rowbuf.append(columnDelimiter);
            }
            rowbuf.append(i);
        }

        for (int i = 0; i < 10; i++) {
            sl.setHeader(header);
            sl.logDelta(rowbuf.toString());
        }

        files = envHome.listFiles(ff);
        assertEquals(files.length, 1);
        /* should only have a header and a data row. */
        assertEquals(
            "Number of rows not expected", getRowCount(testfile), 2);

        rowbuf.append("1");
        sl.setHeader(header);
        sl.logDelta(rowbuf.toString());
        assertEquals(
            "Number of rows not expected", getRowCount(testfile), 3);

        /* simulate a reboot. */
        sl = new StatLogger(
            envHome, filename, fileext, numFiles, rowsInFile);
        sl.setHeader(header);
        sl.logDelta(rowbuf.toString());
        assertEquals(
             "Number of rows not expected", getRowCount(testfile), 3);

        sl.setHeader("a" + header);
        assertEquals(
            "Number of rows not expected", getRowCount(testfile), 1);
        files = envHome.listFiles(ff);
        assertEquals(files.length, 2);
        for (int i = 0; i < 2; i++) {
            sl.logDelta(rowbuf.toString());
        }
        assertEquals(
             "Number of rows not expected", getRowCount(testfile), 2);
    }

    private int getRowCount(File file) throws IOException {
        BufferedReader fr = null;
        int currentRowCount = 0;
        try {
            fr = new BufferedReader(new FileReader(file));
            while (fr.readLine() != null) {
                currentRowCount++;
            }
        } finally {
            if (fr != null) {
                fr.close();
            }
        }
        return currentRowCount;
    }

    class FindFile implements FileFilter {
        String fileprefix;
        FindFile(String fileprefix) {
            this.fileprefix = fileprefix;
        }

        @Override
        public boolean accept(File f) {
            return f.getName().startsWith(fileprefix);
        }
    }
}
