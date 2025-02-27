// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent.dev.blobstorage;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.IntRef;
import com.intellij.serviceContainer.AlreadyDisposedException;
import com.intellij.platform.util.io.storages.mmapped.MMappedFileStorage;
import com.intellij.platform.util.io.storages.mmapped.MMappedFileStorage.Page;
import com.intellij.util.io.blobstorage.ByteBufferReader;
import com.intellij.util.io.blobstorage.ByteBufferWriter;
import com.intellij.util.io.blobstorage.SpaceAllocationStrategy;
import com.intellij.util.io.blobstorage.StreamlinedBlobStorage;
import io.opentelemetry.api.metrics.BatchCallback;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static com.intellij.openapi.vfs.newvfs.persistent.dev.blobstorage.RecordLayout.ActualRecords.recordLayoutForType;
import static com.intellij.openapi.vfs.newvfs.persistent.dev.blobstorage.RecordLayout.ActualRecords.recordSizeTypeByCapacity;
import static com.intellij.openapi.vfs.newvfs.persistent.dev.blobstorage.RecordLayout.OFFSET_BUCKET;
import static com.intellij.util.io.IOUtil.magicWordToASCII;
import static java.lang.invoke.MethodHandles.byteBufferViewVarHandle;
import static java.nio.ByteOrder.nativeOrder;


/**
 * Implements {@link StreamlinedBlobStorage} blobs over {@link MMappedFileStorage} storage.
 * Storage is optimized to store small records (~tens bytes) -- it tries to compress record headers
 * so smaller records have just 2 bytes of overhead because of header. At the same time storage allows
 * record size up to 1Mb large.
 * <p>
 * Storage is NOT thread-safe: it is up to calling code to protect the accesses
 */
public final class StreamlinedBlobStorageOverMMappedFile extends StreamlinedBlobStorageHelper implements StreamlinedBlobStorage {
  private static final Logger LOG = Logger.getInstance(StreamlinedBlobStorageOverMMappedFile.class);

  public static final int STORAGE_VERSION_CURRENT = 1;

  private static final VarHandle INT_HANDLE = byteBufferViewVarHandle(int[].class, nativeOrder())
    .withInvokeExactBehavior();

  //For general persistent format description see comments in superclass

  /* ============== instance fields: ====================================================================== */


  private final @NotNull MMappedFileStorage storage;

  /** Cached storage.pageByOffset(0) value for faster access */
  private transient Page headerPage;


  //==== monitoring fields: =======================================================================================

  private final BatchCallback openTelemetryCallback;


  public StreamlinedBlobStorageOverMMappedFile(@NotNull MMappedFileStorage storage,
                                               @NotNull SpaceAllocationStrategy allocationStrategy) throws IOException {
    super(allocationStrategy, storage.pageSize(), storage.byteOrder());

    this.storage = storage;

    //Important to ask file size _before_ requesting headerPage -- since file is expanded during that request
    long length = storage.actualFileSize();
    if (length > MAX_FILE_LENGTH) {
      throw new IOException(
        "Can't read file[" + storage + "]: too big, " + length + " > Integer.MAX_VALUE * " + OFFSET_BUCKET);
    }

    headerPage = storage.pageByOffset(0L);

    if (length == 0) {//new empty file
      putHeaderInt(HeaderLayout.MAGIC_WORD_OFFSET, MAGIC_WORD);
      putHeaderInt(HeaderLayout.STORAGE_VERSION_OFFSET, STORAGE_VERSION_CURRENT);
      putHeaderInt(HeaderLayout.PAGE_SIZE_OFFSET, pageSize);

      updateNextRecordId(offsetToId(recordsStartOffset()));

      this.wasClosedProperly.set(true);
    }
    else {
      int magicWord = readHeaderInt(HeaderLayout.MAGIC_WORD_OFFSET);
      if (magicWord != MAGIC_WORD) {
        throw new IOException("[" + storage.storagePath() + "] is of incorrect type: " +
                              ".magicWord(=" + magicWord + ", '" + magicWordToASCII(magicWord) + "') != " + MAGIC_WORD + " expected");
      }

      int version = readHeaderInt(HeaderLayout.STORAGE_VERSION_OFFSET);
      if (version != STORAGE_VERSION_CURRENT) {
        throw new IOException(
          "[" + storage.storagePath() + "]: file version(" + version + ") != current impl version (" + STORAGE_VERSION_CURRENT + ")");
      }

      int filePageSize = readHeaderInt(HeaderLayout.PAGE_SIZE_OFFSET);
      if (pageSize != filePageSize) {
        throw new IOException("[" + storage.storagePath() + "]: file created with pageSize=" + filePageSize +
                              " but current storage.pageSize=" + pageSize);
      }

      //No need to copy from the header into field: we read/update header
      // slot directly:
      //updateNextRecordId(readHeaderInt(HeaderLayout.NEXT_RECORD_ID_OFFSET));

      recordsAllocated.set(readHeaderInt(HeaderLayout.RECORDS_ALLOCATED_OFFSET));
      recordsRelocated.set(readHeaderInt(HeaderLayout.RECORDS_RELOCATED_OFFSET));
      recordsDeleted.set(readHeaderInt(HeaderLayout.RECORDS_DELETED_OFFSET));
      totalLiveRecordsPayloadBytes.set(readHeaderLong(HeaderLayout.RECORDS_LIVE_TOTAL_PAYLOAD_SIZE_OFFSET));
      totalLiveRecordsCapacityBytes.set(readHeaderLong(HeaderLayout.RECORDS_LIVE_TOTAL_CAPACITY_SIZE_OFFSET));

      boolean wasClosedProperly = readHeaderInt(HeaderLayout.FILE_STATUS_OFFSET) == FILE_STATUS_PROPERLY_CLOSED;
      this.wasClosedProperly.set(wasClosedProperly);
    }

    putHeaderInt(HeaderLayout.FILE_STATUS_OFFSET, FILE_STATUS_OPENED);

    openTelemetryCallback = setupReportingToOpenTelemetry(storage.storagePath().getFileName(), this);
  }

  @Override
  public int getStorageVersion() {
    return readHeaderInt(HeaderLayout.STORAGE_VERSION_OFFSET);
  }

  @Override
  public int getDataFormatVersion() {
    return readHeaderInt(HeaderLayout.DATA_FORMAT_VERSION_OFFSET);
  }

  @Override
  public void setDataFormatVersion(int expectedVersion) {
    putHeaderInt(HeaderLayout.DATA_FORMAT_VERSION_OFFSET, expectedVersion);
  }


  @Override
  public boolean hasRecord(int recordId,
                           @Nullable IntRef redirectToIdRef) throws IOException {
    if (recordId == NULL_ID) {
      return false;
    }
    checkRecordIdValid(recordId);
    if (!isRecordIdAllocated(recordId)) {
      return false;
    }
    int currentRecordId = recordId;
    for (int i = 0; i < MAX_REDIRECTS; i++) {
      long recordOffset = idToOffset(currentRecordId);
      Page page = storage.pageByOffset(recordOffset);
      int offsetOnPage = storage.toOffsetInPage(recordOffset);
      ByteBuffer buffer = page.rawPageBuffer();
      RecordLayout recordLayout = RecordLayout.recordLayout(buffer, offsetOnPage);
      byte recordType = recordLayout.recordType();

      if (redirectToIdRef != null) {
        redirectToIdRef.set(currentRecordId);
      }

      if (recordType == RecordLayout.RECORD_TYPE_ACTUAL) {
        return true;
      }

      if (recordType == RecordLayout.RECORD_TYPE_MOVED) {
        int redirectToId = recordLayout.redirectToId(buffer, offsetOnPage);
        if (redirectToId == NULL_ID) {
          return false;
        }
        checkRedirectToId(recordId, currentRecordId, redirectToId);
        currentRecordId = redirectToId;
      }
      else {
        throw new AssertionError("RecordType(" + recordType + ") should not appear in the chain: " +
                                 "it is either not implemented yet, or all wrong");
      }
    }
    throw new IOException("record[" + recordId + "].redirectTo chain is too long (>=" + MAX_REDIRECTS + "): circular reference?");
  }

  //MAYBE RC: consider change way of dealing with ByteBuffers: what-if all methods will have same semantics,
  //          i.e. buffer contains payload[0..limit]? I.e. all methods are passing buffers in such a state,
  //          and all methods are returning buffers in such a state?

  /**
   * reader will be called with read-only ByteBuffer set up for reading the record content (payload):
   * i.e. position=0, limit=payload.length. Reader is free to do whatever it likes with the buffer.
   *
   * @param redirectToIdRef if not-null, will contain actual recordId of the record,
   *                        which could be different from recordId passed in if the record was moved (e.g.
   *                        re-allocated in a new place) and recordId used to call the method is now
   *                        outdated. Clients could still use old recordId, but better to replace
   *                        this outdated id with actual one, since it improves performance (at least)
   */
  @Override
  public <Out> Out readRecord(int recordId,
                              @NotNull ByteBufferReader<Out> reader,
                              @Nullable IntRef redirectToIdRef) throws IOException {
    checkRecordIdExists(recordId);
    int currentRecordId = recordId;
    for (int i = 0; i < MAX_REDIRECTS; i++) {
      long recordOffsetInFile = idToOffset(currentRecordId);
      int offsetOnPage = storage.toOffsetInPage(recordOffsetInFile);
      Page page = storage.pageByOffset(recordOffsetInFile);
      ByteBuffer buffer = page.rawPageBuffer();
      RecordLayout recordLayout = RecordLayout.recordLayout(buffer, offsetOnPage);
      byte recordType = recordLayout.recordType();

      if (redirectToIdRef != null) {
        redirectToIdRef.set(currentRecordId); //will be overwritten if we follow .redirectedToId chain
      }

      if (recordType == RecordLayout.RECORD_TYPE_ACTUAL) {
        int recordPayloadLength = recordLayout.length(buffer, offsetOnPage);
        ByteBuffer slice = buffer.slice(offsetOnPage + recordLayout.headerSize(), recordPayloadLength)
          .asReadOnlyBuffer()
          .order(buffer.order());
        return reader.read(slice);
      }

      if (recordType == RecordLayout.RECORD_TYPE_MOVED) {
        int redirectToId = recordLayout.redirectToId(buffer, offsetOnPage);
        checkRedirectToId(recordId, currentRecordId, redirectToId);
        currentRecordId = redirectToId;
      }
      else {
        throw new AssertionError("RecordType(" + recordType + ") should not appear in the chain: " +
                                 "it is either not implemented yet, or all wrong");
      }
    }
    throw new IOException("record[" + recordId + "].redirectTo chain is too long (>=" + MAX_REDIRECTS + "): circular reference?");
  }

  /**
   * Writer is called with writeable ByteBuffer represented current record content (payload).
   * Buffer is prepared for read: position=0, limit=payload.length, capacity=[current record capacity].
   * <br> <br>
   * Writer is free to read and/or modify the buffer, and return it in an 'after puts' state, i.e.
   * position=[#last byte of payload], new payload content = buffer[0..position].
   * <br> <br>
   * NOTE: this implies that even if the writer writes nothing, only reads -- it is still required to
   * set buffer.position=limit, because otherwise storage will treat the buffer state as if record
   * should be set length=0. This is a bit unnatural, so there is a shortcut: if the writer changes
   * nothing, it could just return null.
   * <br> <br>
   * Capacity: if new payload fits into buffer passed in -> it could be written right into it. If new
   * payload requires more space, writer should allocate its own buffer with enough capacity, write
   * new payload into it, and return that buffer (in an 'after puts' state), instead of buffer passed
   * in. Storage will re-allocate space for the record with capacity >= returned buffer capacity.
   *
   * @param expectedRecordSizeHint          hint to a storage about how big data writer intend to write. May be used for allocating buffer
   *                                        of that size. <=0 means 'no hints, use default buffer allocation strategy'
   * @param leaveRedirectOnRecordRelocation if current record is relocated during writing, old record could be either removed right now,
   *                                        or remain as 'redirect-to' record, so new content could still be accesses by old recordId.
   */
  @Override
  public int writeToRecord(int recordId,
                           @NotNull ByteBufferWriter writer,
                           int expectedRecordSizeHint,
                           boolean leaveRedirectOnRecordRelocation) throws IOException {
    //insert new record?
    if (!isValidRecordId(recordId)) {
      ByteBuffer temp = acquireTemporaryBuffer(expectedRecordSizeHint);
      try {
        ByteBuffer bufferWithData = writer.write(temp);
        bufferWithData.flip();

        int recordLength = bufferWithData.limit();
        checkLengthHardLimit(recordLength);
        if (recordLength > maxCapacityForPageSize) {
          throw new IllegalStateException(
            "recordLength(=" + recordLength + ") > maxCapacityForPageSize(=" + maxCapacityForPageSize + ") -- can't fit");
        }

        int capacity = bufferWithData.capacity();
        //Don't check capacity right here -- let allocation strategy first decide how to deal with capacity > MAX
        int requestedRecordCapacity = allocationStrategy.capacity(
          recordLength,
          capacity
        );

        if (requestedRecordCapacity < recordLength) {
          throw new IllegalStateException(
            "Allocation strategy " + allocationStrategy + "(" + recordLength + ", " + capacity + ")" +
            " returns " + requestedRecordCapacity + " < length(=" + recordLength + ")");
        }

        return writeToNewlyAllocatedRecord(bufferWithData, requestedRecordCapacity);
      }
      finally {
        releaseTemporaryBuffer(temp);
      }
    }

    //already existent record
    int currentRecordId = recordId;
    for (int i = 0; i < MAX_REDIRECTS; i++) {
      long recordOffset = idToOffset(currentRecordId);
      int offsetOnPage = storage.toOffsetInPage(recordOffset);
      Page page = storage.pageByOffset(recordOffset);
      ByteBuffer buffer = page.rawPageBuffer();
      RecordLayout recordLayout = RecordLayout.recordLayout(buffer, offsetOnPage);
      byte recordType = recordLayout.recordType();
      if (recordType == RecordLayout.RECORD_TYPE_MOVED) {
        int redirectToId = recordLayout.redirectToId(buffer, offsetOnPage);
        checkRedirectToId(recordId, currentRecordId, redirectToId);
        currentRecordId = redirectToId;
        continue;//hope redirect chains are not too long...
      }
      if (recordType != RecordLayout.RECORD_TYPE_ACTUAL) {
        throw new AssertionError("RecordType(" + recordType + ") should not appear in the chain: " +
                                 "it is either not implemented yet, or all wrong");
      }
      int recordCapacity = recordLayout.capacity(buffer, offsetOnPage);
      int recordActualLength = recordLayout.length(buffer, offsetOnPage);
      //TODO RC: consider 'expectedRecordSizeHint' here? I.e. if expectedRecordSizeHint>record.capacity -> allocate heap buffer
      //         of the size asked, copy actual record content into it?
      int recordPayloadOffset = offsetOnPage + recordLayout.headerSize();
      ByteBuffer recordContent = buffer.slice(recordPayloadOffset, recordCapacity)
        .limit(recordActualLength)
        .order(buffer.order());

      ByteBuffer newRecordContent = writer.write(recordContent);
      if (newRecordContent == null) {
        //returned null means writer decides to skip write -> just return current recordId
        return currentRecordId;
      }

      if (newRecordContent != recordContent) {//writer decides to allocate new buffer for content:
        newRecordContent.flip();
        int newRecordLength = newRecordContent.remaining();
        if (newRecordLength <= recordCapacity) {
          //RC: really, in this case writer should just write data right in the 'recordContent'
          //    buffer, not allocate the new buffer -- but ok, we could deal with it:
          recordLayout.putRecord(buffer, offsetOnPage,
                                 recordCapacity, newRecordLength, NULL_ID, newRecordContent);

          totalLiveRecordsPayloadBytes.addAndGet(newRecordLength - recordActualLength);
        }
        else {//current record is too small for new content -> relocate to a new place
          int newRecordCapacity = allocationStrategy.capacity(newRecordLength, newRecordContent.capacity());
          int newRecordId = writeToNewlyAllocatedRecord(newRecordContent, newRecordCapacity);

          RecordLayout.MovedRecord movedRecordLayout = RecordLayout.MovedRecord.INSTANCE;
          //mark current record as either 'moved' or 'deleted'
          int redirectToId = leaveRedirectOnRecordRelocation ? newRecordId : NULL_ID;
          //Total space occupied by record must remain constant, but record capacity should be
          // changed since MovedRecord has another headerSize than Small|LargeRecord
          int movedRecordCapacity = recordLayout.fullRecordSize(recordCapacity) - movedRecordLayout.headerSize();
          movedRecordLayout.putRecord(buffer, offsetOnPage, movedRecordCapacity, 0, redirectToId, null);

          totalLiveRecordsPayloadBytes.addAndGet(-recordActualLength);
          totalLiveRecordsCapacityBytes.addAndGet(-recordCapacity);
          if (leaveRedirectOnRecordRelocation) {
            recordsRelocated.incrementAndGet();
          }
          else {
            recordsDeleted.incrementAndGet();
          }

          return newRecordId;
        }
      }
      else {//if newRecordContent is null or == recordContent -> changes are already written by writer into the recordContent,
        // we only need to adjust record header:
        recordContent.flip();
        int newRecordLength = recordContent.remaining();
        assert (newRecordLength <= recordCapacity) : newRecordLength + " > " + recordCapacity +
                                                     ": can't be, since recordContent.capacity()==recordCapacity!";
        recordLayout.putLength(buffer, offsetOnPage, newRecordLength);


        totalLiveRecordsPayloadBytes.addAndGet(newRecordLength - recordActualLength);
      }
      return currentRecordId;
    }
    throw new IOException("record[" + recordId + "].redirectTo chain is too long (>=" + MAX_REDIRECTS + "): circular reference?");
  }

  /**
   * Delete record by recordId.
   * <p>
   * Contrary to read/write methods, this method DOES NOT follow redirectTo chain: record to be deleted
   * is the record with id=recordId, redirectToId field is ignored. Why is that: because the main use
   * case of redirectTo chain is to support delayed record removal -- i.e. to give all clients a chance
   * to change their stored recordId to the new one, after the record was moved for some reason. But
   * after all clients have done that, the _stale_ record should be removed (so its space could be
   * reclaimed) -- not the now-actual record referred by redirectTo link. If remove method follows
   * .redirectTo links -- it becomes impossible to remove stale record without affecting its actual
   * counterpart.
   *
   * @throws IllegalStateException if record is already deleted
   */
  @Override
  public void deleteRecord(int recordId) throws IOException {
    checkRecordIdExists(recordId);

    long recordOffset = idToOffset(recordId);
    Page page = storage.pageByOffset(recordOffset);
    int offsetOnPage = storage.toOffsetInPage(recordOffset);
    ByteBuffer buffer = page.rawPageBuffer();
    RecordLayout recordLayout = RecordLayout.recordLayout(buffer, offsetOnPage);
    int recordCapacity = recordLayout.capacity(buffer, offsetOnPage);
    int recordActualLength = recordLayout.length(buffer, offsetOnPage);
    byte recordType = recordLayout.recordType();
    switch (recordType) {
      case RecordLayout.RECORD_TYPE_MOVED -> {
        int redirectToId = recordLayout.redirectToId(buffer, offsetOnPage);
        if (redirectToId == NULL_ID) {
          throw new RecordAlreadyDeletedException("Can't delete record[" + recordId + "]: it was already deleted");
        }

        // (redirectToId=NULL) <=> 'record deleted' ('moved nowhere')
        ((RecordLayout.MovedRecord)recordLayout).putRedirectTo(buffer, offsetOnPage, NULL_ID);
      }
      case RecordLayout.RECORD_TYPE_ACTUAL -> {
        RecordLayout.MovedRecord movedRecordLayout = RecordLayout.MovedRecord.INSTANCE;
        //Total space occupied by record must remain constant, but record capacity should be
        // changed since MovedRecord has another headerSize than Small|LargeRecord
        int deletedRecordCapacity = recordLayout.fullRecordSize(recordCapacity) - movedRecordLayout.headerSize();
        // set (redirectToId=NULL) to mark record as deleted ('moved nowhere')
        movedRecordLayout.putRecord(buffer, offsetOnPage, deletedRecordCapacity, /* length: */ 0, NULL_ID, null);
      }
      default -> throw new AssertionError("RecordType(" + recordType + ") should not appear in the chain: " +
                                          "it is either not implemented yet, or all wrong");
    }

    recordsDeleted.incrementAndGet();
    totalLiveRecordsPayloadBytes.addAndGet(-recordActualLength);
    totalLiveRecordsCapacityBytes.addAndGet(-recordCapacity);
  }

  //TODO int deleteAllForwarders(int recordId) throws IOException;

  /**
   * Scan all records (even deleted one), and deliver their content to processor. ByteBuffer is read-only, and
   * prepared for reading (i.e. position=0, limit=payload.length). For deleted/moved records recordLength is negative
   * see {@link #isRecordActual(int)}.
   * Scanning stops prematurely if processor returns false.
   *
   * @return how many records were processed
   */
  @Override
  public <E extends Exception> int forEach(@NotNull Processor<E> processor) throws IOException, E {
    long storageLength = actualLength();
    int currentId = offsetToId(recordsStartOffset());
    for (int recordNo = 0; ; recordNo++) {
      long recordOffset = idToOffset(currentId);
      Page page = storage.pageByOffset(recordOffset);
      int offsetOnPage = storage.toOffsetInPage(recordOffset);
      ByteBuffer buffer = page.rawPageBuffer();
      RecordLayout recordLayout = RecordLayout.recordLayout(buffer, offsetOnPage);
      byte recordType = recordLayout.recordType();
      int recordCapacity = recordLayout.capacity(buffer, offsetOnPage);
      switch (recordType) {
        case RecordLayout.RECORD_TYPE_ACTUAL, RecordLayout.RECORD_TYPE_MOVED -> {
          int headerSize = recordLayout.headerSize();
          boolean isActual = recordType == RecordLayout.RECORD_TYPE_ACTUAL;
          int recordActualLength = isActual ? recordLayout.length(buffer, offsetOnPage) : -1;
          ByteBuffer slice = isActual ?
                             buffer.slice(offsetOnPage + headerSize, recordActualLength)
                               .asReadOnlyBuffer()
                               .order(buffer.order()) :
                             buffer.slice(offsetOnPage + headerSize, 0)
                               .asReadOnlyBuffer()
                               .order(buffer.order());
          boolean ok = processor.processRecord(currentId, recordCapacity, recordActualLength, slice);
          if (!ok) {
            return recordNo + 1;
          }
        }
        default -> {
          //just skip for now
        }
      }

      long nextRecordOffset = nextRecordOffset(recordOffset, recordLayout, recordCapacity);
      if (nextRecordOffset >= storageLength) {
        return recordNo;
      }

      currentId = offsetToId(nextRecordOffset);
    }
  }

  @Override
  public long sizeInBytes() {
    return actualLength();
  }

  @Override
  public boolean isDirty() {
    //RC: as always, with mapped storage it is tricky to say when it is 'dirty' -- 'cos almost all the
    // writes go directly to the mapped buffer, and mapped buffer dirty/flush management is up to OS.
    // We can manage .dirty flag ourself, but it seems an overhead for (almost) nothing -- there are
    // very few real usages for .isDirty() in mmapped storages. I just define: storage is not dirty
    // by default:
    return false;
  }

  @Override
  public void force() throws IOException {
    checkNotClosed();

    //putHeaderInt(HeaderLayout.NEXT_RECORD_ID_OFFSET, nextRecordId());
    putHeaderInt(HeaderLayout.RECORDS_ALLOCATED_OFFSET, recordsAllocated.get());
    putHeaderInt(HeaderLayout.RECORDS_RELOCATED_OFFSET, recordsRelocated.get());
    putHeaderInt(HeaderLayout.RECORDS_DELETED_OFFSET, recordsDeleted.get());
    putHeaderLong(HeaderLayout.RECORDS_LIVE_TOTAL_PAYLOAD_SIZE_OFFSET, totalLiveRecordsPayloadBytes.get());
    putHeaderLong(HeaderLayout.RECORDS_LIVE_TOTAL_CAPACITY_SIZE_OFFSET, totalLiveRecordsCapacityBytes.get());
  }

  @Override
  public void close() throws IOException {
    //.close() methods are better to be idempotent, i.e. not throw exceptions on repeating calls,
    // but just silently ignore attempts to 'close already closed'. And storage conforms with
    // that. But in .force() we write file status and other header fields, and without .closed
    // flag we'll do that even on already closed storage, which leads to exception.
    if (!closed.get()) {
      //Class in general doesn't provide thread-safety guarantees, and need external synchronization if used in
      // multithreading. But since it uses mmapped files, concurrency errors in closing/reclaiming may lead to
      // JVM crash, not just program bugs -- hence, a bit of protection do no harm:
      synchronized (this) {//also ensures updateNextRecordId() is finished
        if (!closed.get()) {
          putHeaderInt(HeaderLayout.FILE_STATUS_OFFSET, FILE_STATUS_PROPERLY_CLOSED);

          force();

          closed.set(true);

          openTelemetryCallback.close();

          headerPage = null;

          storage.close();
        }
      }
    }
  }

  @Override
  public void closeAndClean() throws IOException {
    close();
    storage.closeAndClean();
  }

  // ============================= implementation: ========================================================================

  @Override
  protected @NotNull Path storagePath() {
    return storage.storagePath();
  }

  // === storage header accessors: ===

  private int readHeaderInt(int offset) {
    assert (0 <= offset && offset <= HeaderLayout.HEADER_SIZE - Integer.BYTES)
      : "header offset(=" + offset + ") must be in [0," + (HeaderLayout.HEADER_SIZE - Integer.BYTES) + "]";
    return headerPage.rawPageBuffer().getInt(offset);
  }

  private void putHeaderInt(int offset,
                            int value) {
    assert (0 <= offset && offset <= HeaderLayout.HEADER_SIZE - Integer.BYTES)
      : "header offset(=" + offset + ") must be in [0," + (HeaderLayout.HEADER_SIZE - Integer.BYTES) + "]";
    headerPage.rawPageBuffer().putInt(offset, value);
  }

  private long readHeaderLong(int offset) {
    assert (0 <= offset && offset <= HeaderLayout.HEADER_SIZE - Long.BYTES)
      : "header offset(=" + offset + ") must be in [0," + (HeaderLayout.HEADER_SIZE - Long.BYTES) + "]";
    return headerPage.rawPageBuffer().getLong(offset);
  }

  private void putHeaderLong(int offset,
                             long value) {
    assert (0 <= offset && offset <= HeaderLayout.HEADER_SIZE - Long.BYTES)
      : "header offset(=" + offset + ") must be in [0," + (HeaderLayout.HEADER_SIZE - Long.BYTES) + "]";
    headerPage.rawPageBuffer().putLong(offset, value);
  }

  /**
   * Actual size of data -- i.e. all allocated records.
   * File size is almost always larger than that because {@link MMappedFileStorage} pre-allocates each page
   * in advance.
   */
  private long actualLength() {
    return idToOffset(nextRecordId());
  }

  @Override
  protected int nextRecordId() {
    if (headerPage == null) {
      throw new AlreadyDisposedException("Storage is closed");
    }
    ByteBuffer headerBuffer = headerPage.rawPageBuffer();
    return (int)INT_HANDLE.getVolatile(headerBuffer, HeaderLayout.NEXT_RECORD_ID_OFFSET);
  }

  //@GuardedBy(this)
  @Override
  protected void updateNextRecordId(int nextRecordId) {
    Page _headerPage = headerPage;
    if (_headerPage == null) {
      throw new AlreadyDisposedException("Storage is closed");
    }
    if (nextRecordId <= NULL_ID) {
      throw new IllegalArgumentException("nextRecordId(=" + nextRecordId + ") must be >0");
    }
    ByteBuffer headerBuffer = _headerPage.rawPageBuffer();
    INT_HANDLE.setVolatile(headerBuffer, HeaderLayout.NEXT_RECORD_ID_OFFSET, nextRecordId);
  }

  // === storage records accessors: ===

  /**
   * content buffer is passed in 'ready for write' state: position=0, limit=[#last byte of payload]
   */
  private int writeToNewlyAllocatedRecord(ByteBuffer content,
                                          int requestedRecordCapacity) throws IOException {
    int pageSize = storage.pageSize();

    int recordLength = content.limit();
    if (recordLength > maxCapacityForPageSize) {
      //Actually, at this point it must be guaranteed recordLength<=maxCapacityForPageSize, but lets check again:
      throw new IllegalStateException(
        "recordLength(=" + recordLength + ") > maxCapacityForPageSize(=" + maxCapacityForPageSize + ") -- can't fit");
    }
    int implementableCapacity = Math.min(requestedRecordCapacity, maxCapacityForPageSize);
    checkCapacityHardLimit(implementableCapacity);


    byte recordSizeType = recordSizeTypeByCapacity(implementableCapacity);
    RecordLayout recordLayout = recordLayoutForType(recordSizeType);
    int fullRecordSize = recordLayout.fullRecordSize(implementableCapacity);
    if (fullRecordSize > pageSize) {
      throw new IllegalArgumentException("record size(header:" + recordLayout.headerSize() + " + capacity:" + implementableCapacity + ")" +
                                         " should be <= pageSize(=" + pageSize + ")");
    }

    IntRef actualRecordSizeRef = new IntRef();//actual record size may be >= requested totalRecordSize
    int newRecordId = allocateSlotForRecord(pageSize, fullRecordSize, actualRecordSizeRef);
    long newRecordOffset = idToOffset(newRecordId);
    int actualRecordSize = actualRecordSizeRef.get();
    int actualRecordCapacity = actualRecordSize - recordLayout.headerSize();
    int newRecordLength = content.remaining();

    //check everything before write anything:
    checkCapacityHardLimit(actualRecordCapacity);
    checkLengthHardLimit(newRecordLength);

    int offsetOnPage = storage.toOffsetInPage(newRecordOffset);
    try {
      Page page = storage.pageByOffset(newRecordOffset);
      recordLayout.putRecord(page.rawPageBuffer(), offsetOnPage,
                             actualRecordCapacity, newRecordLength, NULL_ID,
                             content);
      return newRecordId;
    }
    finally {
      recordsAllocated.incrementAndGet();
      totalLiveRecordsCapacityBytes.addAndGet(actualRecordCapacity);
      totalLiveRecordsPayloadBytes.addAndGet(newRecordLength);
    }
  }

  @Override
  protected void putSpaceFillerRecord(long recordOffset,
                                      int pageSize) throws IOException {
    RecordLayout.PaddingRecord paddingRecord = RecordLayout.PaddingRecord.INSTANCE;

    int offsetInPage = storage.toOffsetInPage(recordOffset);
    int remainingOnPage = pageSize - offsetInPage;

    Page page = storage.pageByOffset(recordOffset);
    int capacity = remainingOnPage - paddingRecord.headerSize();
    paddingRecord.putRecord(page.rawPageBuffer(), offsetInPage, capacity, 0, NULL_ID, null);
  }
}
