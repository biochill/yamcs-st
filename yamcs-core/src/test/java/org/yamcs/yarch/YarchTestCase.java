package org.yamcs.yarch;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Semaphore;

import org.junit.Before;
import org.junit.BeforeClass;
import org.yamcs.YConfiguration;
import org.yamcs.utils.FileUtils;
import org.yamcs.utils.parser.ParseException;
import org.yamcs.yarch.rocksdb.RdbStorageEngine;
import org.yamcs.yarch.streamsql.ExecutionContext;
import org.yamcs.yarch.streamsql.StreamSqlException;
import org.yamcs.yarch.streamsql.StreamSqlParser;

public abstract class YarchTestCase {
    protected StreamSqlParser parser;
    protected YarchDatabaseInstance ydb;
    static boolean littleEndian;
    protected String instance;
    Random random = new Random();
    ExecutionContext context;
    
    @BeforeClass
    public static void setUpYarch() throws Exception {
        YConfiguration.setupTest(null); // reset the prefix if maven runs multiple tests
        // in the same java
        YConfiguration config = YConfiguration.getConfiguration("yamcs");
        if (config.containsKey("littleEndian")) {
            littleEndian = config.getBoolean("littleEndian");
        } else {
            littleEndian = false;
        }
        // org.yamcs.LoggingUtils.enableLogging();
    }

    @Before
    public void setUp() throws Exception {
        YConfiguration config = YConfiguration.getConfiguration("yamcs");
        Path dir = Paths.get(config.getString("dataDir"));
        instance = "yarchtest_" + this.getClass().getSimpleName();
      

        if (YarchDatabase.hasInstance(instance)) {
            YarchDatabase.removeInstance(instance);
            RdbStorageEngine rse = RdbStorageEngine.getInstance();
            if (rse.getTablespace(instance) != null) {
                rse.dropTablespace(instance);
            }
        }

        Path ytdir = dir.resolve(instance);
        Path rdbdir = dir.resolve(instance + ".rdb");

        FileUtils.deleteRecursivelyIfExists(ytdir);
        FileUtils.deleteRecursivelyIfExists(rdbdir);

        if (!ytdir.toFile().mkdirs()) {
            throw new IOException("Cannot create directory " + ytdir);
        }

        ydb = YarchDatabase.getInstance(instance);
        context = new ExecutionContext(ydb);
    }

    /**
     * Reloads the database from disk (without removing the data)
     */
    protected void reloadDb() {
        YarchDatabase.removeInstance(instance);
        RdbStorageEngine rse = RdbStorageEngine.getInstance();
        rse.dropTablespace(instance);
        rse.createTablespace(instance);

        ydb = YarchDatabase.getInstance(instance);
        context = new ExecutionContext(ydb);

    }

    protected void execute(String cmd, Object... args) throws StreamSqlException, ParseException {
        ydb.executeDiscardingResult(cmd, args);
    }

    protected List<Tuple> fetchAllFromTable(String tableName) throws Exception {
        String sname = tableName + "_out_" + random.nextInt(10000);
        ydb.execute("create stream " + sname + " as select * from " + tableName);
        return fetchAll(sname);
    }

    protected List<Tuple> fetchAll(String streamName) throws InterruptedException {
        return fetch(streamName, null);
    }

    /**
     * fetch all tuples from outStream. If inStream is specified, do an inStream.start() after subscribing to outStream
     * otherwise do an outStream.start()
     * 
     * The termination condition is also dictated by the stream where start is used
     * 
     */
    protected List<Tuple> fetch(String outStream, String inStream) throws InterruptedException {
        final List<Tuple> tuples = new ArrayList<>();
        final Semaphore semaphore = new Semaphore(0);
        Stream out = ydb.getStream(outStream);
        if (out == null) {
            throw new IllegalArgumentException("No stream named '" + outStream + "' in instance " + instance);
        }
        Stream streamToStart = null;
        if (inStream != null) {
            streamToStart = ydb.getStream(inStream);
            if (streamToStart == null) {
                throw new IllegalArgumentException("No stream named '" + inStream + "' in instance " + instance);
            }
            streamToStart.addSubscriber(new StreamSubscriber() {

                @Override
                public void streamClosed(Stream stream) {
                    semaphore.release();
                }

                @Override
                public void onTuple(Stream stream, Tuple tuple) {
                }
            });
        } else {
            streamToStart = out;
        }

        out.addSubscriber(new StreamSubscriber() {
            @Override
            public void streamClosed(Stream stream) {
                semaphore.release();
            }

            @Override
            public void onTuple(Stream stream, Tuple tuple) {
                tuples.add(tuple);
            }
        });

        streamToStart.start();

        semaphore.acquire();
        return tuples;
    }

    protected void assertNumElementsEqual(Iterator<?> iter, int k) {
        int num = 0;
        while (iter.hasNext()) {
            num++;
            iter.next();
        }
        assertEquals(k, num);
    }
}
