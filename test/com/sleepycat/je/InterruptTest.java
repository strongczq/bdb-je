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

package com.sleepycat.je;

import static org.junit.Assert.fail;

import java.io.File;

import org.junit.Test;

import com.sleepycat.je.config.EnvironmentParams;
import com.sleepycat.je.util.TestUtils;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;
import com.sleepycat.utilint.StringUtils;

/**
 * @author Paul.Kendall@orionhealth.com
 *
 * This test throws thread interrupts while JE is doing I/O intensive
 * work. When an interrupt is received during various NIO activities, NIO
 * closes the underlying file descriptor. In this multi-threaded test, abruptly
 * closing the file descriptor causes exceptions such as
 * java.nio.ChannelClosedException, because the uninterrupted thread may be in
 * the middle of using that file.
 *
 * JE must convert all such exceptions to
 * com.sleepycat.je.RunRecoveryException.
 */
public class InterruptTest extends TestBase {

    private final File envHome;
    private final int NUM_OPS = 1000;
    private final int NUM_ITERATIONS = 1;

    public InterruptTest() {
        envHome =  SharedTestUtils.getTestDir();
    }

    @Test
    public void testInterruptHandling()
            throws Exception {

        for (int i = 0; i < NUM_ITERATIONS; i++) {
            interruptThreads(i);
        }
    }

    public void interruptThreads(int i)
            throws Exception {

        // TestUtils.removeLogFiles("Loop", envHome, false);
        Environment env = null;
        Database db = null;

        try {
            EnvironmentConfig envConfig = TestUtils.initEnvConfig();
            envConfig.setTransactional(true);
            envConfig.setAllowCreate(true);
            envConfig.setConfigParam
                (EnvironmentParams.ENV_CHECK_LEAKS.getName(), "false");
            env = new Environment(envHome, envConfig);

            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setAllowCreate(true);
            dbConfig.setTransactional(true);
            db = env.openDatabase(null, "testDB" + i, dbConfig);

            ActionThread putter = new ActionThread(env, db, 1) {
                    @Override
                    protected void doStuff(Database db,
                                           Transaction txn,
                                           DatabaseEntry key,
                                           DatabaseEntry value)
                        throws DatabaseException {
                        db.put(txn, key, value);
                    }
                };

            ActionThread deleter = new ActionThread(env, db, 1) {
                    @Override
                    protected void doStuff(Database db,
                                           Transaction txn,
                                           DatabaseEntry key,
                                           DatabaseEntry value)
                        throws DatabaseException {
                        db.delete(txn, key);
                    }
                };

            putter.start();
            Thread.sleep(1000);

            deleter.start();
            Thread.sleep(2000);

            /*
             * Interrupting these threads will catch them in the middle of an
             * NIO operation, expect a RunRecovery exception.
             */
            putter.interrupt();
            deleter.interrupt();

            putter.join();
            deleter.join();
        } finally {
            try {
                if (db != null) {
                    db.close();
                }
            } catch (RunRecoveryException ok) {

                /*
                 * Expect a run recovery exception. Since it will be detected
                 * when we try to close the database, close the environment
                 * now so we can re-start in the same JVM.
                 */
            } catch (Throwable t) {
                t.printStackTrace();
                fail("Should not see any other kind of exception. Iteration=" +
                     i);
            } finally {
                if (env != null) {
                    try {
                        env.close();
                        env = null;
                    } catch (RunRecoveryException ignore) {
                        /* Sometimes the checkpointer can't close down. */
                    }
                }
            }
        }
    }

    abstract class ActionThread extends Thread {
            private final Environment env;
            private final Database db;
        private final int threadNumber;

            public ActionThread(Environment env, Database db, int threadNumber) {
            this.env = env;
            this.db = db;
            this.threadNumber = threadNumber;
            }

            @Override
        public void run() {
            int i=0;
            Transaction txn = null;
            try {
                for (; i < NUM_OPS; i++) {
                    txn = env.beginTransaction(null, null);
                    DatabaseEntry key = new DatabaseEntry();
                    key.setData
                        (StringUtils.toUTF8("" + threadNumber * 10000 + i));
                    DatabaseEntry value = new DatabaseEntry();
                    value.setData(new byte[8192]);
                    doStuff(db, txn, key, value);
                    Thread.sleep(10);
                    txn.commit();
                    txn = null;
                }
            } catch (InterruptedException e) {
                /* possible outcome. */
            } catch (RunRecoveryException e) {
                /* possible outcome. */
            } catch (DatabaseException e) {
                /* possible outcome. */
                //System.out.println("Put to " + i);
                //e.printStackTrace();
            } finally {
                try {
                    if (txn != null) {
                        txn.abort();
                    }
                } catch (DatabaseException ignored) {
                }
            }
            }

        abstract protected void doStuff(Database db,
                                        Transaction txn,
                                        DatabaseEntry key,
                                        DatabaseEntry value)
            throws DatabaseException;
    }
}
