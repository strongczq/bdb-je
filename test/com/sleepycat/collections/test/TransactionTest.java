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

package com.sleepycat.collections.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sleepycat.collections.CurrentTransaction;
import com.sleepycat.collections.StoredCollections;
import com.sleepycat.collections.StoredContainer;
import com.sleepycat.collections.StoredIterator;
import com.sleepycat.collections.StoredList;
import com.sleepycat.collections.StoredSortedMap;
import com.sleepycat.collections.TransactionRunner;
import com.sleepycat.collections.TransactionWorker;
import com.sleepycat.compat.DbCompat;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.CursorConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockConflictException;
import com.sleepycat.je.OperationStatus;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.TransactionConfig;
import com.sleepycat.util.RuntimeExceptionWrapper;
import com.sleepycat.util.test.SharedTestUtils;
import com.sleepycat.util.test.TestBase;
import com.sleepycat.util.test.TestEnv;

/**
 * @author Mark Hayes
 */
public class TransactionTest extends TestBase {

    private static final Long ONE = new Long(1);
    private static final Long TWO = new Long(2);
    private static final Long THREE = new Long(3);


    private Environment env;
    private CurrentTransaction currentTxn;
    private Database store;
    private StoredSortedMap map;
    private TestStore testStore = TestStore.BTREE_UNIQ;

    public TransactionTest() {

        customName = "TransactionTest";
    }

    @Before
    public void setUp()
        throws Exception {

        super.setUp();
        env = TestEnv.TXN.open("TransactionTests");
        currentTxn = CurrentTransaction.getInstance(env);
        store = testStore.open(env, dbName(0));
        map = new StoredSortedMap(store, testStore.getKeyBinding(),
                                  testStore.getValueBinding(), true);
    }

    @After
    public void tearDown() {

        try {
            if (store != null) {
                store.close();
            }
            if (env != null) {
                env.close();
            }
        } catch (Exception e) {
            System.out.println("Ignored exception during tearDown: " + e);
        } finally {
            /* Ensure that GC can cleanup. */
            store = null;
            env = null;
            currentTxn = null;
            map = null;
            testStore = null;
        }
    }

    private String dbName(int i) {

        return "txn-test-" + i;
    }

    @Test
    public void testGetters()
        throws Exception {

        assertNotNull(env);
        assertNotNull(currentTxn);
        assertNull(currentTxn.getTransaction());

        currentTxn.beginTransaction(null);
        assertNotNull(currentTxn.getTransaction());
        currentTxn.commitTransaction();
        assertNull(currentTxn.getTransaction());

        currentTxn.beginTransaction(null);
        assertNotNull(currentTxn.getTransaction());
        currentTxn.abortTransaction();
        assertNull(currentTxn.getTransaction());

        // read-uncommitted property should be inherited

        assertTrue(!isReadUncommitted(map));
        assertTrue(!isReadUncommitted(map.values()));
        assertTrue(!isReadUncommitted(map.keySet()));
        assertTrue(!isReadUncommitted(map.entrySet()));

        StoredSortedMap other = (StoredSortedMap)
            StoredCollections.configuredMap
                (map, CursorConfig.READ_UNCOMMITTED);
        assertTrue(isReadUncommitted(other));
        assertTrue(isReadUncommitted(other.values()));
        assertTrue(isReadUncommitted(other.keySet()));
        assertTrue(isReadUncommitted(other.entrySet()));
        assertTrue(!isReadUncommitted(map));
        assertTrue(!isReadUncommitted(map.values()));
        assertTrue(!isReadUncommitted(map.keySet()));
        assertTrue(!isReadUncommitted(map.entrySet()));

        // read-committed property should be inherited

        assertTrue(!isReadCommitted(map));
        assertTrue(!isReadCommitted(map.values()));
        assertTrue(!isReadCommitted(map.keySet()));
        assertTrue(!isReadCommitted(map.entrySet()));

        other = (StoredSortedMap)
            StoredCollections.configuredMap
                (map, CursorConfig.READ_COMMITTED);
        assertTrue(isReadCommitted(other));
        assertTrue(isReadCommitted(other.values()));
        assertTrue(isReadCommitted(other.keySet()));
        assertTrue(isReadCommitted(other.entrySet()));
        assertTrue(!isReadCommitted(map));
        assertTrue(!isReadCommitted(map.values()));
        assertTrue(!isReadCommitted(map.keySet()));
        assertTrue(!isReadCommitted(map.entrySet()));
    }

    @Test
    public void testTransactional()
        throws Exception {

        // is transactional because DB_AUTO_COMMIT was passed to
        // Database.open()
        //
        assertTrue(map.isTransactional());
        store.close();
        store = null;

        // is not transactional
        //
        DatabaseConfig dbConfig = new DatabaseConfig();
        DbCompat.setTypeBtree(dbConfig);
        dbConfig.setAllowCreate(true);
        Database db = DbCompat.testOpenDatabase
            (env, null, dbName(1), null, dbConfig);
        map = new StoredSortedMap(db, testStore.getKeyBinding(),
                                      testStore.getValueBinding(), true);
        assertTrue(!map.isTransactional());
        map.put(ONE, ONE);
        readCheck(map, ONE, ONE);
        db.close();

        // is transactional
        //
        dbConfig.setTransactional(true);
        currentTxn.beginTransaction(null);
        db = DbCompat.testOpenDatabase
            (env, currentTxn.getTransaction(), dbName(2), null, dbConfig);
        currentTxn.commitTransaction();
        map = new StoredSortedMap(db, testStore.getKeyBinding(),
                                      testStore.getValueBinding(), true);
        assertTrue(map.isTransactional());
        currentTxn.beginTransaction(null);
        map.put(ONE, ONE);
        readCheck(map, ONE, ONE);
        currentTxn.commitTransaction();
        db.close();
    }

    @Test
    public void testExceptions()
        throws Exception {

        try {
            currentTxn.commitTransaction();
            fail();
        } catch (IllegalStateException expected) {}

        try {
            currentTxn.abortTransaction();
            fail();
        } catch (IllegalStateException expected) {}
    }

    @Test
    public void testNested()
        throws Exception {

        if (!DbCompat.NESTED_TRANSACTIONS) {
            return;
        }
        assertNull(currentTxn.getTransaction());

        Transaction txn1 = currentTxn.beginTransaction(null);
        assertNotNull(txn1);
        assertTrue(txn1 == currentTxn.getTransaction());

        assertNull(map.get(ONE));
        assertNull(map.put(ONE, ONE));
        assertEquals(ONE, map.get(ONE));

        Transaction txn2 = currentTxn.beginTransaction(null);
        assertNotNull(txn2);
        assertTrue(txn2 == currentTxn.getTransaction());
        assertTrue(txn1 != txn2);

        assertNull(map.put(TWO, TWO));
        assertEquals(TWO, map.get(TWO));

        Transaction txn3 = currentTxn.beginTransaction(null);
        assertNotNull(txn3);
        assertTrue(txn3 == currentTxn.getTransaction());
        assertTrue(txn1 != txn2);
        assertTrue(txn1 != txn3);
        assertTrue(txn2 != txn3);

        assertNull(map.put(THREE, THREE));
        assertEquals(THREE, map.get(THREE));

        Transaction txn = currentTxn.abortTransaction();
        assertTrue(txn == txn2);
        assertTrue(txn == currentTxn.getTransaction());
        assertNull(map.get(THREE));
        assertEquals(TWO, map.get(TWO));

        txn3 = currentTxn.beginTransaction(null);
        assertNotNull(txn3);
        assertTrue(txn3 == currentTxn.getTransaction());
        assertTrue(txn1 != txn2);
        assertTrue(txn1 != txn3);
        assertTrue(txn2 != txn3);

        assertNull(map.put(THREE, THREE));
        assertEquals(THREE, map.get(THREE));

        txn = currentTxn.commitTransaction();
        assertTrue(txn == txn2);
        assertTrue(txn == currentTxn.getTransaction());
        assertEquals(THREE, map.get(THREE));
        assertEquals(TWO, map.get(TWO));

        txn = currentTxn.commitTransaction();
        assertTrue(txn == txn1);
        assertTrue(txn == currentTxn.getTransaction());
        assertEquals(THREE, map.get(THREE));
        assertEquals(TWO, map.get(TWO));
        assertEquals(ONE, map.get(ONE));

        txn = currentTxn.commitTransaction();
        assertNull(txn);
        assertNull(currentTxn.getTransaction());
        assertEquals(THREE, map.get(THREE));
        assertEquals(TWO, map.get(TWO));
        assertEquals(ONE, map.get(ONE));
    }

    @Test
    public void testRunnerCommit()
        throws Exception {

        commitTest(false);
    }

    @Test
    public void testExplicitCommit()
        throws Exception {

        commitTest(true);
    }

    private void commitTest(final boolean explicit)
        throws Exception {

        final TransactionRunner runner = new TransactionRunner(env);
        runner.setAllowNestedTransactions(DbCompat.NESTED_TRANSACTIONS);

        assertNull(currentTxn.getTransaction());

        runner.run(new TransactionWorker() {
            public void doWork() throws Exception {
                final Transaction txn1 = currentTxn.getTransaction();
                assertNotNull(txn1);
                assertNull(map.put(ONE, ONE));
                assertEquals(ONE, map.get(ONE));

                runner.run(new TransactionWorker() {
                    public void doWork() throws Exception {
                        final Transaction txn2 = currentTxn.getTransaction();
                        assertNotNull(txn2);
                        if (DbCompat.NESTED_TRANSACTIONS) {
                            assertTrue(txn1 != txn2);
                        } else {
                            assertTrue(txn1 == txn2);
                        }
                        assertNull(map.put(TWO, TWO));
                        assertEquals(TWO, map.get(TWO));
                        assertEquals(ONE, map.get(ONE));
                        if (DbCompat.NESTED_TRANSACTIONS && explicit) {
                            currentTxn.commitTransaction();
                        }
                    }
                });

                Transaction txn3 = currentTxn.getTransaction();
                assertSame(txn1, txn3);

                assertEquals(TWO, map.get(TWO));
                assertEquals(ONE, map.get(ONE));
            }
        });

        assertNull(currentTxn.getTransaction());
    }

    @Test
    public void testRunnerAbort()
        throws Exception {

        abortTest(false);
    }

    @Test
    public void testExplicitAbort()
        throws Exception {

        abortTest(true);
    }

    private void abortTest(final boolean explicit)
        throws Exception {

        final TransactionRunner runner = new TransactionRunner(env);
        runner.setAllowNestedTransactions(DbCompat.NESTED_TRANSACTIONS);

        assertNull(currentTxn.getTransaction());

        runner.run(new TransactionWorker() {
            public void doWork() throws Exception {
                final Transaction txn1 = currentTxn.getTransaction();
                assertNotNull(txn1);
                assertNull(map.put(ONE, ONE));
                assertEquals(ONE, map.get(ONE));

                if (DbCompat.NESTED_TRANSACTIONS) {
                    try {
                        runner.run(new TransactionWorker() {
                            public void doWork() throws Exception {
                                final Transaction txn2 =
                                        currentTxn.getTransaction();
                                assertNotNull(txn2);
                                assertTrue(txn1 != txn2);
                                assertNull(map.put(TWO, TWO));
                                assertEquals(TWO, map.get(TWO));
                                if (explicit) {
                                    currentTxn.abortTransaction();
                                } else {
                                    throw new IllegalArgumentException(
                                                                "test-abort");
                                }
                            }
                        });
                        assertTrue(explicit);
                    } catch (IllegalArgumentException e) {
                        assertTrue(!explicit);
                        assertEquals("test-abort", e.getMessage());
                    }
                }

                Transaction txn3 = currentTxn.getTransaction();
                assertSame(txn1, txn3);

                assertEquals(ONE, map.get(ONE));
                assertNull(map.get(TWO));
            }
        });

        assertNull(currentTxn.getTransaction());
    }

    @Test
    public void testReadCommittedCollection()
        throws Exception {

        StoredSortedMap degree2Map = (StoredSortedMap)
            StoredCollections.configuredSortedMap
                (map, CursorConfig.READ_COMMITTED);

        // original map is not read-committed
        assertTrue(!isReadCommitted(map));

        // all read-committed containers are read-uncommitted
        assertTrue(isReadCommitted(degree2Map));
        assertTrue(isReadCommitted
            (StoredCollections.configuredMap
                (map, CursorConfig.READ_COMMITTED)));
        assertTrue(isReadCommitted
            (StoredCollections.configuredCollection
                (map.values(), CursorConfig.READ_COMMITTED)));
        assertTrue(isReadCommitted
            (StoredCollections.configuredSet
                (map.keySet(), CursorConfig.READ_COMMITTED)));
        assertTrue(isReadCommitted
            (StoredCollections.configuredSortedSet
                ((SortedSet) map.keySet(),
                 CursorConfig.READ_COMMITTED)));

        if (DbCompat.RECNO_METHOD) {
            // create a list just so we can call configuredList()
            Database listStore = TestStore.RECNO_RENUM.open(env, "foo");
            List list = new StoredList(listStore, TestStore.VALUE_BINDING,
                                       true);
            assertTrue(isReadCommitted
                (StoredCollections.configuredList
                    (list, CursorConfig.READ_COMMITTED)));
            listStore.close();
        }

        map.put(ONE, ONE);
        doReadCommitted(degree2Map, null);
    }

    private static boolean isReadCommitted(Object container) {
        StoredContainer storedContainer = (StoredContainer) container;
        /* We can't use getReadCommitted until is is added to DB core. */
        return storedContainer.getCursorConfig() != null &&
               storedContainer.getCursorConfig().getReadCommitted();
    }

    @Test
    public void testReadCommittedTransaction()
        throws Exception {

        TransactionConfig config = new TransactionConfig();
        config.setReadCommitted(true);
        doReadCommitted(map, config);
    }

    private void doReadCommitted(final StoredSortedMap degree2Map,
                                 TransactionConfig txnConfig)
        throws Exception {

        map.put(ONE, ONE);
        TransactionRunner runner = new TransactionRunner(env);
        runner.setTransactionConfig(txnConfig);
        assertNull(currentTxn.getTransaction());
        runner.run(new TransactionWorker() {
            public void doWork() throws Exception {
                assertNotNull(currentTxn.getTransaction());

                /* Do a read-committed get(), the lock is not retained. */
                assertEquals(ONE, degree2Map.get(ONE));

                /*
                 * If we were not using read-committed, the following write of
                 * key ONE with an auto-commit transaction would self-deadlock
                 * since two transactions in the same thread would be
                 * attempting to lock the same key, one for write and one for
                 * read.  This test passes if we do not deadlock.
                 */
                DatabaseEntry key = new DatabaseEntry();
                DatabaseEntry value = new DatabaseEntry();
                testStore.getKeyBinding().objectToEntry(ONE, key);
                testStore.getValueBinding().objectToEntry(TWO, value);
                store.put(null, key, value);
            }
        });
        assertNull(currentTxn.getTransaction());
    }

    @Test
    public void testReadUncommittedCollection()
        throws Exception {

        StoredSortedMap dirtyMap = (StoredSortedMap)
            StoredCollections.configuredSortedMap
                (map, CursorConfig.READ_UNCOMMITTED);

        // original map is not read-uncommitted
        assertTrue(!isReadUncommitted(map));

        // all read-uncommitted containers are read-uncommitted
        assertTrue(isReadUncommitted(dirtyMap));
        assertTrue(isReadUncommitted
            (StoredCollections.configuredMap
                (map, CursorConfig.READ_UNCOMMITTED)));
        assertTrue(isReadUncommitted
            (StoredCollections.configuredCollection
                (map.values(), CursorConfig.READ_UNCOMMITTED)));
        assertTrue(isReadUncommitted
            (StoredCollections.configuredSet
                (map.keySet(), CursorConfig.READ_UNCOMMITTED)));
        assertTrue(isReadUncommitted
            (StoredCollections.configuredSortedSet
                ((SortedSet) map.keySet(), CursorConfig.READ_UNCOMMITTED)));

        if (DbCompat.RECNO_METHOD) {
            // create a list just so we can call configuredList()
            Database listStore = TestStore.RECNO_RENUM.open(env, "foo");
            List list = new StoredList(listStore, TestStore.VALUE_BINDING,
                                       true);
            assertTrue(isReadUncommitted
                (StoredCollections.configuredList
                    (list, CursorConfig.READ_UNCOMMITTED)));
            listStore.close();
        }

        doReadUncommitted(dirtyMap);
    }

    private static boolean isReadUncommitted(Object container) {
        StoredContainer storedContainer = (StoredContainer) container;
        return storedContainer.getCursorConfig() != null &&
               storedContainer.getCursorConfig().getReadUncommitted();
    }

    @Test
    public void testReadUncommittedTransaction()
        throws Exception {

        TransactionRunner runner = new TransactionRunner(env);
        TransactionConfig config = new TransactionConfig();
        config.setReadUncommitted(true);
        runner.setTransactionConfig(config);
        assertNull(currentTxn.getTransaction());
        runner.run(new TransactionWorker() {
            public void doWork() throws Exception {
                assertNotNull(currentTxn.getTransaction());
                doReadUncommitted(map);
            }
        });
        assertNull(currentTxn.getTransaction());
    }

    /**
     * Tests that the CurrentTransaction static WeakHashMap does indeed allow
     * GC to reclaim tine environment when it is closed.  At one point this was
     * not working because the value object in the map has a reference to the
     * environment.  This was fixed by wrapping the Environment in a
     * WeakReference.  [#15444]
     *
     * This test only succeeds intermittently, probably due to its reliance
     * on the GC call.
     */
    @Test
    public void testCurrentTransactionGC()
        throws Exception {

        /*
         * This test can have indeterminate results because it depends on
         * a finalize count, so it's not part of the default run.
         */
        if (!SharedTestUtils.runLongTests()) {
            return;
        }

        final StringBuilder finalizedFlag = new StringBuilder();

        class MyEnv extends Environment {

            /**
             * @throws FileNotFoundException from DB core.
             */
            MyEnv(File home, EnvironmentConfig config)
                throws DatabaseException, FileNotFoundException {

                super(home, config);
            }

            @Override
            protected void finalize() {
                finalizedFlag.append('.');
            }
        }

        MyEnv myEnv = new MyEnv(env.getHome(), env.getConfig());
        CurrentTransaction myCurrTxn = CurrentTransaction.getInstance(myEnv);

        store.close();
        store = null;
        map = null;

        env.close();
        env = null;

        myEnv.close();
        myEnv = null;

        myCurrTxn = null;
        currentTxn = null;

        for (int i = 0; i < 10; i += 1) {
            byte[] x = null;
            try {
                 x = new byte[Integer.MAX_VALUE - 1];
            } catch (OutOfMemoryError expected) {
            }
            assertNull(x);
            System.gc();
        }

        for (int i = 0; i < 10; i += 1) {
            System.gc();
        }

        assertTrue(finalizedFlag.length() > 0);
    }

    private synchronized void doReadUncommitted(StoredSortedMap dirtyMap)
        throws Exception {

        // start thread one
        ReadUncommittedThreadOne t1 = new ReadUncommittedThreadOne(env, this);
        t1.start();
        wait();

        // put ONE
        synchronized (t1) { t1.notify(); }
        wait();
        readCheck(dirtyMap, ONE, ONE);
        assertTrue(!dirtyMap.isEmpty());

        // abort ONE
        synchronized (t1) { t1.notify(); }
        t1.join();
        readCheck(dirtyMap, ONE, null);
        assertTrue(dirtyMap.isEmpty());

        // start thread two
        ReadUncommittedThreadTwo t2 = new ReadUncommittedThreadTwo(env, this);
        t2.start();
        wait();

        // put TWO
        synchronized (t2) { t2.notify(); }
        wait();
        readCheck(dirtyMap, TWO, TWO);
        assertTrue(!dirtyMap.isEmpty());

        // commit TWO
        synchronized (t2) { t2.notify(); }
        t2.join();
        readCheck(dirtyMap, TWO, TWO);
        assertTrue(!dirtyMap.isEmpty());
    }

    private static class ReadUncommittedThreadOne extends Thread {

        private final CurrentTransaction currentTxn;
        private final TransactionTest parent;
        private final StoredSortedMap map;

        private ReadUncommittedThreadOne(Environment env,
                                         TransactionTest parent) {

            this.currentTxn = CurrentTransaction.getInstance(env);
            this.parent = parent;
            this.map = parent.map;
        }

        @Override
        public synchronized void run() {

            try {
                assertNull(currentTxn.getTransaction());
                assertNotNull(currentTxn.beginTransaction(null));
                assertNotNull(currentTxn.getTransaction());
                readCheck(map, ONE, null);
                synchronized (parent) { parent.notify(); }
                wait();

                // put ONE
                assertNull(map.put(ONE, ONE));
                readCheck(map, ONE, ONE);
                synchronized (parent) { parent.notify(); }
                wait();

                // abort ONE
                assertNull(currentTxn.abortTransaction());
                assertNull(currentTxn.getTransaction());
            } catch (Exception e) {
                throw new RuntimeExceptionWrapper(e);
            }
        }
    }

    private static class ReadUncommittedThreadTwo extends Thread {

        private final Environment env;
        private final CurrentTransaction currentTxn;
        private final TransactionTest parent;
        private final StoredSortedMap map;

        private ReadUncommittedThreadTwo(Environment env,
                                         TransactionTest parent) {

            this.env = env;
            this.currentTxn = CurrentTransaction.getInstance(env);
            this.parent = parent;
            this.map = parent.map;
        }

        @Override
        public synchronized void run() {

            try {
                final TransactionRunner runner = new TransactionRunner(env);
                final Object thread = this;
                assertNull(currentTxn.getTransaction());

                runner.run(new TransactionWorker() {
                    public void doWork() throws Exception {
                        assertNotNull(currentTxn.getTransaction());
                        readCheck(map, TWO, null);
                        synchronized (parent) { parent.notify(); }
                        thread.wait();

                        // put TWO
                        assertNull(map.put(TWO, TWO));
                        readCheck(map, TWO, TWO);
                        synchronized (parent) { parent.notify(); }
                        thread.wait();

                        // commit TWO
                    }
                });
                assertNull(currentTxn.getTransaction());
            } catch (Exception e) {
                throw new RuntimeExceptionWrapper(e);
            }
        }
    }

    private static void readCheck(StoredSortedMap checkMap, Object key,
                                  Object expect) {
        if (expect == null) {
            assertNull(checkMap.get(key));
            assertTrue(checkMap.tailMap(key).isEmpty());
            assertTrue(!checkMap.tailMap(key).containsKey(key));
            assertTrue(!checkMap.keySet().contains(key));
            assertTrue(checkMap.duplicates(key).isEmpty());
            Iterator i = checkMap.keySet().iterator();
            try {
                while (i.hasNext()) {
                    assertTrue(!key.equals(i.next()));
                }
            } finally { StoredIterator.close(i); }
        } else {
            assertEquals(expect, checkMap.get(key));
            assertEquals(expect, checkMap.tailMap(key).get(key));
            assertTrue(!checkMap.tailMap(key).isEmpty());
            assertTrue(checkMap.tailMap(key).containsKey(key));
            assertTrue(checkMap.keySet().contains(key));
            assertTrue(checkMap.values().contains(expect));
            assertTrue(!checkMap.duplicates(key).isEmpty());
            assertTrue(checkMap.duplicates(key).contains(expect));
            Iterator i = checkMap.keySet().iterator();
            try {
                boolean found = false;
                while (i.hasNext()) {
                    if (expect.equals(i.next())) {
                        found = true;
                    }
                }
                assertTrue(found);
            }
            finally { StoredIterator.close(i); }
        }
    }

    /**
     * Tests transaction retries performed by TransationRunner.
     *
     * This test is too sensitive to how lock conflict detection works on JE to
     * make it work properly on DB core.
     */
    /* <!-- begin JE only --> */
    @Test
    public void testRetry()
        throws Exception {

        final AtomicInteger tries = new AtomicInteger();
        final AtomicInteger releaseLockAfterTries = new AtomicInteger();
        final Transaction txn1 = env.beginTransaction(null, null);
        final Cursor txn1Cursor =
            store.openCursor(txn1, CursorConfig.READ_COMMITTED);
        final TransactionRunner runner = new TransactionRunner(env);

        final TransactionWorker worker = new TransactionWorker() {
            public void doWork() throws Exception {
                tries.getAndIncrement();
                if (releaseLockAfterTries.get() == tries.get()) {
                    /* With READ_COMMITTED, getNext releases the lock. */
                    txn1Cursor.getNext(new DatabaseEntry(),
                                       new DatabaseEntry(), null);
                }
                Transaction txn2 = currentTxn.getTransaction();
                assertNotNull(txn2);
                txn2.setLockTimeout(10 * 1000); /* Speed up the test. */
                assertTrue(txn1 != txn2);
                map.put(ONE, TWO);
            }
        };

        /* Insert ONE and TWO with auto-commit.  Leave no records locked. */
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry value = new DatabaseEntry();

        testStore.getKeyBinding().objectToEntry(ONE, key);
        testStore.getValueBinding().objectToEntry(ONE, value);
        OperationStatus status = store.put(null, key, value);
        assertSame(OperationStatus.SUCCESS, status);

        testStore.getKeyBinding().objectToEntry(TWO, key);
        testStore.getValueBinding().objectToEntry(TWO, value);
        status = store.put(null, key, value);
        assertSame(OperationStatus.SUCCESS, status);

        /*
         * Disable timouts in txn1 so that the timeout will occur in txn2 (in
         * TransactionRunner).
         */
        txn1.setLockTimeout(0);

        /*
         * Read ONE with txn1 and leave it locked.  Expect the default number
         * of retries and then a lock conflict.
         */
        status = txn1Cursor.getFirst(key, value, null);
        assertSame(OperationStatus.SUCCESS, status);
        int expectTries = TransactionRunner.DEFAULT_MAX_RETRIES + 1;
        releaseLockAfterTries.set(0);
        tries.set(0);
        try {
            runner.run(worker);
            fail();
        } catch (LockConflictException expected) {
        }
        assertEquals(expectTries, tries.get());

        /* Same as above but use a custom number of retries. */
        status = txn1Cursor.getFirst(key, value, null);
        assertSame(OperationStatus.SUCCESS, status);
        expectTries = 5;
        runner.setMaxRetries(expectTries - 1);
        releaseLockAfterTries.set(0);
        tries.set(0);
        try {
            runner.run(worker);
            fail();
        } catch (LockConflictException expected) {
        }
        assertEquals(expectTries, tries.get());

        /*
         * In this variant the TransactionWorker will move the txn1Cursor
         * forward to free the lock after 3 tries.  The 4th try will succeed,
         * so do not expect a lock conflict.
         */
        status = txn1Cursor.getFirst(key, value, null);
        assertSame(OperationStatus.SUCCESS, status);
        expectTries = 3;
        releaseLockAfterTries.set(expectTries);
        tries.set(0);
        runner.run(worker);
        assertEquals(expectTries, tries.get());

        /* Cleanup. */
        txn1Cursor.close();
        txn1.abort();
    }
    /* <!-- end JE only --> */

    /**
     * Tests transaction retries performed by TransationRunner.
     *
     * This test is too sensitive to how lock conflict detection works on JE to
     * make it work properly on DB core.
     */
    /* <!-- begin JE only --> */
    @Test
    public void testExceptionHandler()
        throws Exception {

        class RetriesExceeded extends Exception {}
        final int customMaxRetries = TransactionRunner.DEFAULT_MAX_RETRIES * 2;

        final TransactionRunner runner = new TransactionRunner(env) {
            @Override
            public int handleException(Exception e,
                                       int retries,
                                       int maxRetries)
                throws Exception {
                if (e instanceof LockConflictException) {
                    if (retries >= maxRetries) {
                        throw new RetriesExceeded();
                    } else {
                        return customMaxRetries;
                    }
                } else {
                    throw e;
                }
            }
        };

        final Transaction txn1 = env.beginTransaction(null, null);
        final AtomicInteger tries = new AtomicInteger();

        final TransactionWorker worker = new TransactionWorker() {
            public void doWork() throws Exception {
                tries.getAndIncrement();
                Transaction txn2 = currentTxn.getTransaction();
                assertNotNull(txn2);
                txn2.setLockTimeout(10 * 1000); /* Speed up the test. */
                assertTrue(txn1 != txn2);
                map.put(ONE, TWO);
            }
        };

        /* Insert ONE with txn1.  Leave the record locked. */
        DatabaseEntry key = new DatabaseEntry();
        DatabaseEntry value = new DatabaseEntry();

        testStore.getKeyBinding().objectToEntry(ONE, key);
        testStore.getValueBinding().objectToEntry(ONE, value);
        OperationStatus status = store.put(txn1, key, value);
        assertSame(OperationStatus.SUCCESS, status);

        /*
         * Disable timouts in txn1 so that the timeout will occur in txn2 (in
         * TransactionRunner).
         */
        txn1.setLockTimeout(0);

        /* Expect the custom number of retries and the custom exception. */
        tries.set(0);
        try {
            runner.run(worker);
            fail();
        } catch (RetriesExceeded expected) {
        }
        assertEquals(customMaxRetries + 1, tries.get());

        /* Cleanup. */
        txn1.abort();
    }
    /* <!-- end JE only --> */
}
