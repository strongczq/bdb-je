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

package com.sleepycat.je.evictor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;

import com.sleepycat.je.util.TestUtils;

/**
 * Measure the amount of actual (RSS) memory used by the off-heap allocator.
 *
 * Allocates a given number of blocks. Then the loops forever freeing and
 * allocating every other block. Each malloc uses a random size between the
 * given min/max sizes (if equal, the size is fixed).
 *
 * The output has the current date/time, cache size (how much memory we think
 * is used) and the RSS size (the amount of memory used by the process as
 * reported by 'ps'). These two last values should be about the same, if our
 * size estimates are correct.
 *
 * Usage:
 *   java com.sleepycat.je.evictor.MeasureOffHeapMemory \
 *    number-of-blocks
 *    min-block-size
 *    max-block-size
 *
 * The Java heap must be large number to hold an array of block pointers, each
 * of which uses 8 bytes, so 8 * number-of-blocks.
 *
 * Example run to use around 220 GB:
 *  java -Xmx4g -cp test.jar com.sleepycat.je.evictor.MeasureOffHeapMemory \
 *    400000000 40 1040
 */
public class MeasureOffHeapMemory {

    private static final DateFormat DATE_FORMAT =
        new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    public static void main(final String[] args) {
        try {
            new MeasureOffHeapMemory(args).run();
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private final long[] ids;
    private final int minSize;
    private final int range;
    private final OffHeapAllocator allocator;
    private final String[] psCommand;
    private final Random rnd;

    private MeasureOffHeapMemory(final String[] args) throws Exception {

        ids = new long[Integer.parseInt(args[0])];
        minSize = Integer.parseInt(args[1]);
        final int maxSize = Integer.parseInt(args[2]);
        range = maxSize - minSize;

        final OffHeapAllocatorFactory factory = new OffHeapAllocatorFactory();
        allocator = factory.getDefaultAllocator();

        psCommand = new String[] { "ps", "o", "rss=", "" + getPid() };
        rnd = new Random(123);
    }

    private void run() throws Exception {

        /* Causes the initial RSS size to include the heap size. */
        fillTheHeap();

        final long initialRss = getRSS();

        System.out.format(
            "Allocs/Frees per iteration: %,d Initial RSS: %,d\n",
            ids.length / 2, initialRss);

        for (int i = 0; i < ids.length; i += 1) {

            final int size =
                (range == 0) ? minSize : (minSize + rnd.nextInt(range));

            try {
                ids[i] = allocator.allocate(size);
            } catch (Throwable e) {
                System.err.println("Unable to allocate block " + i);
                throw e;
            }
        }

        while (true) {

            final long initialTime = System.currentTimeMillis();

            for (int i = 0; i < ids.length; i += 2) {

                allocator.free(ids[i]);

                final int size =
                    (range == 0) ? minSize : (minSize + rnd.nextInt(range));

                try {
                    ids[i] = allocator.allocate(size);
                } catch (Throwable e) {
                    System.err.println("Unable to allocate block " + i);
                    throw e;
                }
            }

            final long finalTime = System.currentTimeMillis();
            final long finalRss = getRSS();

            System.out.format(
                "%s Millis: %,d Cache: %,d RSS: %,d\n",
                DATE_FORMAT.format(new Date(finalTime)),
                finalTime - initialTime,
                allocator.getUsedBytes(),
                finalRss - initialRss);
        }
    }

    private static void fillTheHeap() {
        Throwable e = null;
        while (true) {
            try {
                e = new Throwable(e);
            } catch (OutOfMemoryError e1) {
                return;
            }
        }
    }

    private long getRSS() throws Exception {

        final ProcessBuilder pBuilder = new ProcessBuilder(psCommand);
        final Process process = pBuilder.start();

        final BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream()));

        String result = "";
        for (String line = reader.readLine(); line != null;
             line = reader.readLine()) {
            result += line;
        }

        result = result.trim();

        try {
            return Long.parseLong(result) * 1024;
        } catch (NumberFormatException e) {
            throw new RuntimeException(result, e);
        }
    }

    private static int getPid() throws Exception {

        final int pid = TestUtils.getPid(MeasureOffHeapMemory.class.getName());

        if (pid < 0) {
            throw new RuntimeException("Couldn't get my PID");
        }

        return pid;
    }
}
