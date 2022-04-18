/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.raft.jraft.storage.impl;

import static java.util.Arrays.copyOfRange;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ignite.lang.IgniteLogger;
import org.apache.ignite.raft.jraft.conf.Configuration;
import org.apache.ignite.raft.jraft.conf.ConfigurationEntry;
import org.apache.ignite.raft.jraft.conf.ConfigurationManager;
import org.apache.ignite.raft.jraft.entity.EnumOutter;
import org.apache.ignite.raft.jraft.entity.LogEntry;
import org.apache.ignite.raft.jraft.entity.LogId;
import org.apache.ignite.raft.jraft.entity.codec.LogEntryDecoder;
import org.apache.ignite.raft.jraft.entity.codec.LogEntryEncoder;
import org.apache.ignite.raft.jraft.option.LogStorageOptions;
import org.apache.ignite.raft.jraft.option.RaftOptions;
import org.apache.ignite.raft.jraft.storage.LogStorage;
import org.apache.ignite.raft.jraft.util.BytesUtil;
import org.apache.ignite.raft.jraft.util.Describer;
import org.apache.ignite.raft.jraft.util.ExecutorServiceHelper;
import org.apache.ignite.raft.jraft.util.Requires;
import org.apache.ignite.raft.jraft.util.Utils;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.Slice;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * Log storage that shares rocksdb instance with other log storages.
 * Stores key with groupId prefix to distinguish them from keys that belongs to other storages.
 */
public class RocksDbSharedLogStorage implements LogStorage, Describer {
    /** Logger. */
    private static final IgniteLogger LOG = IgniteLogger.forClass(RocksDbSharedLogStorage.class);

    static {
        RocksDB.loadLibrary();
    }

    /**
     * An empty write context.
     */
    private static class EmptyWriteContext implements WriteContext {
        static EmptyWriteContext INSTANCE = new EmptyWriteContext();
    }

    /**
     * VarHandle that gives the access to the elements of a {@code byte[]} array viewed as if it was a {@code long[]}
     * array.
     */
    private static final VarHandle LONG_ARRAY_HANDLE = MethodHandles.byteArrayViewVarHandle(
            long[].class,
            ByteOrder.BIG_ENDIAN
    );

    /**
     * First log index and last log index key in configuration column family.
     */
    private static final byte[] FIRST_LOG_IDX_KEY = Utils.getBytes("meta/firstLogIndex");

    /** Shared db instance. */
    private final RocksDB db;

    /** Shared configuration column family handle. */
    private final ColumnFamilyHandle confHandle;

    /** Shared data column family handle. */
    private final ColumnFamilyHandle dataHandle;

    /** Write options. */
    private final WriteOptions writeOptions;

    /** Start prefix. */
    private final byte[] groupStartPrefix;

    /** End prefix. */
    private final byte[] groupEndPrefix;

    /** Raft group start bound. */
    private final Slice groupStartBound;

    /** Raft group end bound. */
    private final Slice groupEndBound;

    /** RW lock. */
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    /** Read lock. */
    private final Lock readLock = this.readWriteLock.readLock();

    /** Write lock. */
    private final Lock writeLock = this.readWriteLock.writeLock();

    /** Executor that handles prefix truncation. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** Log entry encoder. */
    private LogEntryEncoder logEntryEncoder;

    /** Log entry decoder. */
    private LogEntryDecoder logEntryDecoder;

    /** First log index. */
    private volatile long firstLogIndex = 1;

    /** First log index loaded flag. */
    private volatile boolean hasLoadFirstLogIndex;

    /** Constructor. */
    RocksDbSharedLogStorage(
            RocksDB db,
            ColumnFamilyHandle confHandle,
            ColumnFamilyHandle dataHandle,
            String groupId,
            RaftOptions raftOptions
    ) {
        Requires.requireTrue(
                groupId.indexOf(0) == -1,
                "Raft group id " + groupId + " must not contain char(0)"
        );
        Requires.requireTrue(
                groupId.indexOf(1) == -1,
                "Raft group id " + groupId + " must not contain char(1)"
        );

        this.db = db;
        this.confHandle = confHandle;
        this.dataHandle = dataHandle;
        this.groupStartPrefix = (groupId + (char) 0).getBytes(StandardCharsets.UTF_8);
        this.groupEndPrefix = (groupId + (char) 1).getBytes(StandardCharsets.UTF_8);
        this.groupStartBound = new Slice(groupStartPrefix);
        this.groupEndBound = new Slice(groupEndPrefix);

        this.writeOptions = new WriteOptions();
        this.writeOptions.setSync(raftOptions.isSync());
    }

    /** {@inheritDoc} */
    @Override
    public boolean init(LogStorageOptions opts) {
        Requires.requireNonNull(opts.getConfigurationManager(), "Null conf manager");
        Requires.requireNonNull(opts.getLogEntryCodecFactory(), "Null log entry codec factory");
        this.writeLock.lock();
        try {
            this.logEntryDecoder = opts.getLogEntryCodecFactory().decoder();
            this.logEntryEncoder = opts.getLogEntryCodecFactory().encoder();
            Requires.requireNonNull(this.logEntryDecoder, "Null log entry decoder");
            Requires.requireNonNull(this.logEntryEncoder, "Null log entry encoder");

            return initAndLoad(opts.getConfigurationManager());
        } finally {
            this.writeLock.unlock();
        }
    }

    private boolean initAndLoad(ConfigurationManager configurationManager) {
        this.hasLoadFirstLogIndex = false;
        this.firstLogIndex = 1;
        load(configurationManager);
        return onInitLoaded();
    }

    private void load(ConfigurationManager confManager) {
        try (
                var readOptions = new ReadOptions().setIterateUpperBound(groupEndBound);
                RocksIterator it = this.db.newIterator(this.confHandle, readOptions)
        ) {
            it.seek(groupStartPrefix);
            while (it.isValid()) {
                byte[] keyWithPrefix = it.key();
                byte[] ks = getKey(keyWithPrefix);
                byte[] bs = it.value();

                // LogEntry index
                if (ks.length == 8) {
                    LogEntry entry = this.logEntryDecoder.decode(bs);
                    if (entry != null) {
                        if (entry.getType() == EnumOutter.EntryType.ENTRY_TYPE_CONFIGURATION) {
                            ConfigurationEntry confEntry = new ConfigurationEntry();
                            confEntry.setId(new LogId(entry.getId().getIndex(), entry.getId().getTerm()));
                            confEntry.setConf(new Configuration(entry.getPeers(), entry.getLearners()));
                            if (entry.getOldPeers() != null) {
                                confEntry.setOldConf(new Configuration(entry.getOldPeers(), entry.getOldLearners()));
                            }
                            if (confManager != null) {
                                confManager.add(confEntry);
                            }
                        }
                    } else {
                        LOG.warn(
                                "Fail to decode conf entry at index {}, the log data is: {}.",
                                ((long) LONG_ARRAY_HANDLE.get(ks, 0)),
                                BytesUtil.toHex(bs)
                        );
                    }
                } else {
                    if (Arrays.equals(FIRST_LOG_IDX_KEY, ks)) {
                        setFirstLogIndex((long) LONG_ARRAY_HANDLE.get(bs, 0));
                        truncatePrefixInBackground(0L, this.firstLogIndex);
                    } else {
                        LOG.warn("Unknown entry in configuration storage key={}, value={}.", BytesUtil.toHex(ks),
                                BytesUtil.toHex(bs));
                    }
                }
                it.next();
            }
        }
    }

    private byte[] getKey(byte[] ks) {
        return copyOfRange(ks, groupStartPrefix.length, ks.length);
    }

    private void setFirstLogIndex(long index) {
        this.firstLogIndex = index;
        this.hasLoadFirstLogIndex = true;
    }

    /**
     * Save the first log index into conf column family.
     */
    private boolean saveFirstLogIndex(long firstLogIndex) {
        this.readLock.lock();
        try {
            byte[] vs = new byte[8];
            LONG_ARRAY_HANDLE.set(vs, 0, firstLogIndex);
            this.db.put(this.confHandle, this.writeOptions, createKey(FIRST_LOG_IDX_KEY), vs);
            return true;
        } catch (RocksDBException e) {
            LOG.error("Fail to save first log index {}.", e, firstLogIndex);
            return false;
        } finally {
            this.readLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void shutdown() {
        this.writeLock.lock();

        try {
            onShutdown();
        } finally {
            this.writeLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public long getFirstLogIndex() {
        this.readLock.lock();

        try {
            if (this.hasLoadFirstLogIndex) {
                return this.firstLogIndex;
            }

            try (
                    var readOptions = new ReadOptions().setIterateUpperBound(groupEndBound);
                    RocksIterator it = this.db.newIterator(this.dataHandle, readOptions)
            ) {
                it.seek(groupStartPrefix);

                if (it.isValid()) {
                    byte[] key = getKey(it.key());
                    long ret = (long) LONG_ARRAY_HANDLE.get(key, 0);
                    saveFirstLogIndex(ret);
                    setFirstLogIndex(ret);
                    return ret;
                }

                return 1L;
            }
        } finally {
            this.readLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public long getLastLogIndex() {
        this.readLock.lock();

        try (
                var readOptions = new ReadOptions().setIterateLowerBound(groupStartBound);
                RocksIterator it = this.db.newIterator(this.dataHandle, readOptions)
        ) {
            it.seekForPrev(groupEndPrefix);

            if (it.isValid()) {
                byte[] key = getKey(it.key());
                return (long) LONG_ARRAY_HANDLE.get(key, 0);
            }

            return 0L;
        } finally {
            this.readLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public LogEntry getEntry(long index) {
        this.readLock.lock();
        try {
            if (this.hasLoadFirstLogIndex && index < this.firstLogIndex) {
                return null;
            }

            byte[] keyBytes = createKey(index);
            byte[] bs = getValueFromRocksDb(keyBytes);

            if (bs != null) {
                LogEntry entry = this.logEntryDecoder.decode(bs);
                if (entry != null) {
                    return entry;
                } else {
                    LOG.error("Bad log entry format for index={}, the log data is: {}.", index, BytesUtil.toHex(bs));
                    // invalid data remove? TODO https://issues.apache.org/jira/browse/IGNITE-14832
                    return null;
                }
            }
        } catch (RocksDBException e) {
            LOG.error("Fail to get log entry at index {}.", e, index);
        } finally {
            this.readLock.unlock();
        }
        return null;
    }

    protected byte[] getValueFromRocksDb(byte[] keyBytes) throws RocksDBException {
        return this.db.get(this.dataHandle, keyBytes);
    }

    /** {@inheritDoc} */
    @Override
    public long getTerm(long index) {
        LogEntry entry = getEntry(index);
        if (entry != null) {
            return entry.getId().getTerm();
        }
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public boolean appendEntry(LogEntry entry) {
        if (entry.getType() == EnumOutter.EntryType.ENTRY_TYPE_CONFIGURATION) {
            return executeBatch(batch -> addConfBatch(entry, batch));
        } else {
            this.readLock.lock();
            try {
                if (this.db == null) {
                    LOG.warn("DB not initialized or destroyed.");
                    return false;
                }
                WriteContext writeCtx = newWriteContext();
                long logIndex = entry.getId().getIndex();
                byte[] valueBytes = this.logEntryEncoder.encode(entry);
                byte[] newValueBytes = onDataAppend(logIndex, valueBytes, writeCtx);
                writeCtx.startJob();
                this.db.put(this.dataHandle, this.writeOptions, createKey(logIndex), newValueBytes);
                writeCtx.joinAll();
                if (newValueBytes != valueBytes) {
                    doSync();
                }
                return true;
            } catch (RocksDBException | IOException e) {
                LOG.error("Fail to append entry.", e);
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                this.readLock.unlock();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public int appendEntries(List<LogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }

        int entriesCount = entries.size();

        boolean ret = executeBatch(batch -> {
            WriteContext writeCtx = newWriteContext();

            for (LogEntry entry : entries) {
                if (entry.getType() == EnumOutter.EntryType.ENTRY_TYPE_CONFIGURATION) {
                    addConfBatch(entry, batch);
                } else {
                    writeCtx.startJob();
                    addDataBatch(entry, batch, writeCtx);
                }
            }

            writeCtx.joinAll();
            doSync();
        });

        if (ret) {
            return entriesCount;
        } else {
            return 0;
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean truncateSuffix(long lastIndexKept) {
        this.readLock.lock();
        try {
            try {
                onTruncateSuffix(lastIndexKept);
            } finally {
                this.db.deleteRange(this.dataHandle, this.writeOptions, createKey(lastIndexKept + 1),
                        createKey(getLastLogIndex() + 1));
                this.db.deleteRange(this.confHandle, this.writeOptions, createKey(lastIndexKept + 1),
                        createKey(getLastLogIndex() + 1));
            }
            return true;
        } catch (RocksDBException | IOException e) {
            LOG.error("Fail to truncateSuffix {}.", e, lastIndexKept);
        } finally {
            this.readLock.unlock();
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean reset(long nextLogIndex) {
        if (nextLogIndex <= 0) {
            throw new IllegalArgumentException("Invalid next log index.");
        }
        this.writeLock.lock();

        LogEntry entry = getEntry(nextLogIndex);
        try {
            db.deleteRange(dataHandle, groupStartPrefix, groupEndPrefix);
            db.deleteRange(confHandle, groupStartPrefix, groupEndPrefix);

            onReset(nextLogIndex);

            if (initAndLoad(null)) {
                if (entry == null) {
                    entry = new LogEntry();
                    entry.setType(EnumOutter.EntryType.ENTRY_TYPE_NO_OP);
                    entry.setId(new LogId(nextLogIndex, 0));
                    LOG.warn("Entry not found for nextLogIndex {} when reset.", nextLogIndex);
                }
                return appendEntry(entry);
            } else {
                return false;
            }
        } catch (RocksDBException e) {
            LOG.error("Fail to reset next log index.", e);
            return false;
        } finally {
            this.writeLock.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean truncatePrefix(long firstIndexKept) {
        this.readLock.lock();
        try {
            long startIndex = getFirstLogIndex();
            boolean ret = saveFirstLogIndex(firstIndexKept);

            if (ret) {
                setFirstLogIndex(firstIndexKept);
            }

            truncatePrefixInBackground(startIndex, firstIndexKept);

            return ret;
        } finally {
            this.readLock.unlock();
        }
    }

    private void addConfBatch(LogEntry entry, WriteBatch batch) throws RocksDBException {
        byte[] ks = createKey(entry.getId().getIndex());
        byte[] content = this.logEntryEncoder.encode(entry);
        batch.put(this.dataHandle, ks, content);
        batch.put(this.confHandle, ks, content);
    }

    /**
     * Execute write batch template.
     *
     * @param template write batch template
     */
    private boolean executeBatch(WriteBatchTemplate template) {
        this.readLock.lock();
        if (this.db == null) {
            LOG.warn("DB not initialized or destroyed.");
            this.readLock.unlock();
            return false;
        }
        try (WriteBatch batch = new WriteBatch()) {
            template.execute(batch);
            this.db.write(this.writeOptions, batch);
        } catch (RocksDBException e) {
            LOG.error("Execute batch failed with rocksdb exception.", e);
            return false;
        } catch (IOException e) {
            LOG.error("Execute batch failed with io exception.", e);
            return false;
        } catch (InterruptedException e) {
            LOG.error("Execute batch failed with interrupt.", e);
            Thread.currentThread().interrupt();
            return false;
        } finally {
            this.readLock.unlock();
        }
        return true;
    }

    private void addDataBatch(LogEntry entry, WriteBatch batch,
            WriteContext ctx) throws RocksDBException, IOException, InterruptedException {
        long logIndex = entry.getId().getIndex();
        byte[] content = this.logEntryEncoder.encode(entry);
        batch.put(this.dataHandle, createKey(logIndex), onDataAppend(logIndex, content, ctx));
    }

    private void truncatePrefixInBackground(long startIndex, long firstIndexKept) {
        // delete logs in background.
        Utils.runInThread(executor, () -> {
            this.readLock.lock();
            try {
                if (this.db == null) {
                    return;
                }
                onTruncatePrefix(startIndex, firstIndexKept);
                this.db.deleteRange(this.dataHandle, createKey(startIndex), createKey(firstIndexKept));
                this.db.deleteRange(this.confHandle, createKey(startIndex), createKey(firstIndexKept));
            } catch (RocksDBException | IOException e) {
                LOG.error("Fail to truncatePrefix {}.", e, firstIndexKept);
            } finally {
                this.readLock.unlock();
            }
        });
    }

    /**
     * Called upon closing the storage.
     */
    protected void onShutdown() {
        ExecutorServiceHelper.shutdownAndAwaitTermination(executor);

        writeOptions.close();
        groupEndBound.close();
        groupStartBound.close();
    }

    private byte[] createKey(byte[] key) {
        var buffer = new byte[groupStartPrefix.length + key.length];

        System.arraycopy(groupStartPrefix, 0, buffer, 0, groupStartPrefix.length);
        System.arraycopy(key, 0, buffer, groupStartPrefix.length, key.length);

        return buffer;
    }

    private byte[] createKey(long index) {
        byte[] ks = new byte[groupStartPrefix.length + Long.BYTES];
        System.arraycopy(groupStartPrefix, 0, ks, 0, groupStartPrefix.length);
        LONG_ARRAY_HANDLE.set(ks, groupStartPrefix.length, index);
        return ks;
    }

    private void doSync() throws IOException, InterruptedException {
        onSync();
    }

    protected WriteContext newWriteContext() {
        return EmptyWriteContext.INSTANCE;
    }

    /**
     * Called before appending data entry.
     *
     * @param logIndex the log index
     * @param value the data value in log entry.
     * @return the new value
     */
    protected byte[] onDataAppend(long logIndex, byte[] value, WriteContext ctx) throws IOException, InterruptedException {
        ctx.finishJob();
        return value;
    }

    /**
     * Called when sync data into file system.
     */
    protected void onSync() throws IOException, InterruptedException {
    }

    /**
     * Called after loading configuration into conf manager.
     */
    protected boolean onInitLoaded() {
        return true;
    }

    /**
     * Called after resetting db.
     *
     * @param nextLogIndex next log index
     */
    protected void onReset(long nextLogIndex) {
    }

    /**
     * Called after truncating prefix logs in rocksdb.
     *
     * @param startIndex the start index
     * @param firstIndexKept the first index to kept
     */
    protected void onTruncatePrefix(long startIndex, long firstIndexKept) throws RocksDBException,
            IOException {
    }

    /**
     * Called after truncating suffix logs in rocksdb.
     *
     * @param lastIndexKept the last index to kept
     */
    protected void onTruncateSuffix(long lastIndexKept) throws RocksDBException, IOException {
    }

    /**
     * Write batch template.
     */
    private interface WriteBatchTemplate {

        void execute(WriteBatch batch) throws RocksDBException, IOException, InterruptedException;
    }

    /**
     * Write context.
     */
    public interface WriteContext {
        /**
         * Start a sub job.
         */
        default void startJob() {
        }

        /**
         * Finish a sub job.
         */
        default void finishJob() {
        }

        /**
         * Adds a callback that will be invoked after all sub jobs finish.
         */
        default void addFinishHook(Runnable r) {

        }

        /**
         * Set an exception to context.
         *
         * @param e exception
         */
        default void setError(Exception e) {
        }

        /**
         * Wait for all sub jobs finish.
         */
        default void joinAll() throws InterruptedException, IOException {
        }
    }

    /** {@inheritDoc} */
    @Override
    public void describe(final Printer out) {
        this.readLock.lock();
        try {
            if (this.db != null) {
                out.println(this.db.getProperty("rocksdb.stats"));
            }
        } catch (final RocksDBException e) {
            out.println(e);
        } finally {
            this.readLock.unlock();
        }
    }
}
