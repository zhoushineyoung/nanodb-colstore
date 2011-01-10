package edu.caltech.nanodb.storage.heapfile;


import org.apache.log4j.Logger;

import edu.caltech.nanodb.storage.DBPage;


/**
 * This class contains some constants for where different values live in the
 * data pages of a table file.
 */
public class DataPage {
    /** A logging object for reporting anything interesting that happens. */
    private static Logger logger = Logger.getLogger(DataPage.class);


    /**
     * The offset in the data page where the number of slots in the slot table
     * is stored.
     */
    public static final int OFFSET_NUM_SLOTS = 0;


    /**
     * This offset-value is stored into a slot when it is empty.  It is set to
     * zero because this is where the page's slot-count is stored and therefore
     * this is obviously an invalid offset for a tuple to be located at.
     */
    public static final int EMPTY_SLOT = 0;


    /**
     * Initialize a newly allocated data page.  Currently this involves setting
     * the number of slots to 0.  There is no other internal structure in data
     * pages at this point.
     */
    public static void initNewPage(DBPage dbPage) {
        setNumSlots(dbPage, 0);
    }


    public static int getNumSlots(DBPage dbPage) {
        return dbPage.readUnsignedShort(OFFSET_NUM_SLOTS);
    }


    public static void setNumSlots(DBPage dbPage, int numSlots) {
        dbPage.writeShort(OFFSET_NUM_SLOTS, numSlots);
    }


    /**
     * This static helper function returns the index where the slot list ends in
     * the data page.
     */
    public static int getSlotsEndIndex(DBPage dbPage) {
        int numSlots = getNumSlots(dbPage);

        // The count at the start is two bytes, and each slot's offset is two
        // bytes.
        return (1 + numSlots) * 2;
    }


    public static int getSlotValue(DBPage dbPage, int slot) {
        int numSlots = getNumSlots(dbPage);

        if (slot < 0 || slot >= numSlots) {
            throw new IllegalArgumentException("Valid slots are in range [0," +
                numSlots + ").  Got " + slot);
        }

        return dbPage.readUnsignedShort((1 + slot) * 2);
    }


    public static void setSlotValue(DBPage dbPage, int slot, int value) {
        int numSlots = getNumSlots(dbPage);

        if (slot < 0 || slot >= numSlots) {
            throw new IllegalArgumentException("Valid slots are in range [0," +
                numSlots + ").  Got " + slot);
        }

        dbPage.writeShort((1 + slot) * 2, value);
    }


    public static int getSlotIndexFromOffset(DBPage dbPage, int offset)
        throws IllegalArgumentException {

        if (offset % 2 != 0) {
            throw new IllegalArgumentException(
                "Slots occur at even indexes (each slot is a short).");
        }

        int slot = (offset - 2) / 2;
        int numSlots = getNumSlots(dbPage);

        if (slot < 0 || slot >= numSlots) {
            throw new IllegalArgumentException("Valid slots are in range [0," +
                numSlots + ").  Got " + slot);
        }

        return slot;
    }


    /**
     * This static helper function returns the index of where tuple data
     * currently starts in the specified data page.
     */
    public static int getTupleDataStart(DBPage dbPage) {
        int numSlots = getNumSlots(dbPage);

        // If there are no tuples in this page, "data start" is the top of the
        // page.
        int dataStart = dbPage.getPageSize();

        int slot = numSlots - 1;
        while (slot >= 0) {
            int slotValue = getSlotValue(dbPage, slot);
            if (slotValue != EMPTY_SLOT) {
                dataStart = slotValue;
                break;
            }

            --slot;
        }

        return dataStart;
    }


    public static int getTupleLength(DBPage dbPage, int slot) {
        int numSlots = getNumSlots(dbPage);

        if (slot < 0 || slot >= numSlots) {
            throw new IllegalArgumentException(
                "Valid slots are in range [0," + slot + ").  Got " + slot);
        }

        int tupleStart = getSlotValue(dbPage, slot);

        if (tupleStart == EMPTY_SLOT)
            throw new IllegalArgumentException("Slot " + slot + " is empty.");

        int tupleLength = -1;

        int prevSlot = slot - 1;
        while (prevSlot >= 0) {
            int prevTupleStart = getSlotValue(dbPage, prevSlot);
            if (prevTupleStart != EMPTY_SLOT) {

                // Earlier slots have higher offsets.  (Yes it's weird.)
                tupleLength = prevTupleStart - tupleStart;

                break;
            }

            prevSlot--;
        }

        if (prevSlot < 0) {
            // The specified slot held the last tuple in the page.
            tupleLength = dbPage.getPageSize() - tupleStart;
        }

        return tupleLength;
    }


    /**
     * This static helper function returns the amount of free space in
     * a tuple data page.
     */
    public static int getFreeSpaceInPage(DBPage dbPage) {
        return getTupleDataStart(dbPage) - getSlotsEndIndex(dbPage);
    }


  /**
   * This static helper function inserts a sequence of bytes from the
   * tuple data in the page, sliding tuple data below the offset down to
   * create a gap.  Because tuples below the specified offset will actually
   * move, some of the slots in the page may also need to be modified.
   * <p>
   * The new space is initialized to all zero values.
   */
  public static void insertTupleDataRange(DBPage dbPage, int off, int len) {

    int tupDataStart = getTupleDataStart(dbPage);

    if (off < tupDataStart) {
      throw new IllegalArgumentException("Specified offset " + off +
        " is not actually in the tuple data portion of this page " +
        "(data starts at offset " + tupDataStart + ").");
    }

    if (len < 0)
      throw new IllegalArgumentException("Length must not be negative.");

    if (len > getFreeSpaceInPage(dbPage)) {
      throw new IllegalArgumentException("Specified length " + len +
        " is larger than amount of free space in this page (" +
        getFreeSpaceInPage(dbPage) + " bytes).");
    }

    byte[] pageData = dbPage.getPageData();

    // If off == tupDataStart then there's no need to move anything.
    if (off > tupDataStart) {
      // Move the data in the range [tupDataStart, off) to
      // [tupDataStart - len, off - len).  Thus there will be a gap in the
      // range [off - len, off) after the operation is completed.

      System.arraycopy(pageData, tupDataStart,
                       pageData, tupDataStart - len, off - tupDataStart);
    }

    // Zero out the gap that was just created.
    int startOff = off - len;
    for (int i = 0; i < len; i++)
      pageData[startOff + i] = 0;

    // Update affected slots; this includes all slots below the specified
    // offset.  The update is easy; slot values just move down by len bytes.

    int numSlots = getNumSlots(dbPage);
    for (int iSlot = 0; iSlot < numSlots; iSlot++) {

      int slotOffset = getSlotValue(dbPage, iSlot);
      if (slotOffset != EMPTY_SLOT) {
        if (slotOffset < off) {
          // Update this slot's offset.
          slotOffset -= len;
          setSlotValue(dbPage, iSlot, slotOffset);
        }
        else {
          // All remaining slots should be unaffected since they are stored in
          // increasing order of offset.
          break;
        }
      }
    }
  }


  /**
   * This static helper function removes a sequence of bytes from the current
   * tuple data in the page, sliding tuple data below the offset forward to
   * fill in the gap.  Because tuples below the specified offset will actually
   * move, some of the slots in the page may also need to be modified.
   **/
  public static void deleteTupleDataRange(DBPage dbPage, int off, int len) {
    int tupDataStart = getTupleDataStart(dbPage);

    if (off < tupDataStart) {
      throw new IllegalArgumentException("Specified offset " + off +
        " is not actually in the tuple data portion of this page " +
        "(data starts at offset " + tupDataStart + ").");
    }

    if (len < 0)
      throw new IllegalArgumentException("Length must not be negative.");

    if (dbPage.getPageSize() - off < len) {
      throw new IllegalArgumentException("Specified length " + len +
        " is larger than size of tuple data in this page (" +
        (dbPage.getPageSize() - off) + " bytes).");
    }

    // Move the data in the range [tupDataStart, off) to
    // [tupDataStart + len, off + len).

    byte[] pageData = dbPage.getPageData();
    System.arraycopy(pageData, tupDataStart,
                     pageData, tupDataStart + len, off - tupDataStart);

    // Update affected slots; this includes all slots below the specified
    // offset.  The update is easy; slot values just move up by len bytes.

    int numSlots = getNumSlots(dbPage);
    for (int iSlot = 0; iSlot < numSlots; iSlot++) {

      int slotOffset = dbPage.readUnsignedShort(2 * (iSlot + 1));
      if (slotOffset != EMPTY_SLOT) {
        if (slotOffset <= off) {
          // Update this slot's offset.
          slotOffset += len;
          dbPage.writeShort(2 * (iSlot + 1), slotOffset);
        }
        else {
          // All remaining slots should be unaffected since they are stored in
          // increasing order of offset.
          break;
        }
      }
    }
  }


  /**
   * Update the data page so that it has space for a new tuple of the
   * specified size.  The new tuple is assigned a slot (whose index is
   * returned by this method), and the space for the tuple is initialized
   * to all zero values.
   *
   * @param dbPage The data page to store the new tuple in.
   *
   * @param len The length of the new tuple's data.
   *
   * @return The slot-index for the new tuple.  The offset to the start
   *         of the requested space is available via that slot.  (Use
   *         {@link #getSlotValue} to retrieve that offset.)
   **/
  public static int allocNewTuple(DBPage dbPage, int len) {

      if (len < 0) {
          throw new IllegalArgumentException(
              "Length must be nonnegative; got " + len);
      }

      // The amount of free space we need in the database page, if we are
      // going to add the new tuple.
      int spaceNeeded = len;

      logger.debug("Allocating space for new " + len + "-byte tuple.");

      // Search through the current list of slots in the page.  If a slot
      // is marked as "empty" then we can use that slot.  Otherwise, we
      // will need to add a new slot to the end of the list.

      int slot;
      int numSlots = getNumSlots(dbPage);

      logger.debug("Current number of slots on page:  " + numSlots);

      // This variable tracks where the new tuple should END.  It starts
      // as the page-size, and gets moved down past each valid tuple in
      // the page, until we find an available slot in the page.
      int newTupleEnd = dbPage.getPageSize();

      for (slot = 0; slot < numSlots; slot++) {
          // currSlotValue is either the start of that slot's tuple-data,
          // or it is set to EMPTY_SLOT.
          int currSlotValue = getSlotValue(dbPage, slot);

          if (currSlotValue == EMPTY_SLOT)
              break;
          else
              newTupleEnd = currSlotValue;
      }

      // First make sure we actually have enough space for the new tuple.

      if (slot == numSlots) {
          // We'll need to add a new slot to the list.  Make sure there's
          // room.
          spaceNeeded += 2;
      }

      if (spaceNeeded > getFreeSpaceInPage(dbPage)) {
          // TODO:  Switch this to a checked exception?
          throw new IllegalArgumentException(
              "Space needed for new tuple (" + spaceNeeded +
              " bytes) is larger than the free space in this page (" +
              getFreeSpaceInPage(dbPage) + " bytes).");
      }

      // Now we know we have space for the tuple.  Update the slot list,
      // and the update page's layout to make room for the new tuple.

      if (slot == numSlots) {
          logger.debug("No empty slot available.  Adding a new slot.");

          // Add the new slot to the page, and update the total number of
          // slots.
          numSlots++;
          setNumSlots(dbPage, numSlots);
      }

      logger.debug(String.format(
          "Tuple will get slot %d.  Final number of slots:  %d",
          slot, numSlots));

      int newTupleStart = newTupleEnd - len;

      logger.debug(String.format(
          "New tuple of %d bytes will reside at location [%d, %d).",
          len, newTupleStart, newTupleEnd));

      // Make room for the new tuple's data to be stored into.  Since
      // tuples are stored from the END of the page going backwards, we
      // specify the new tuple's END index, and the tuple's length.
      // (Note:  This call also updates all affected slots whose offsets
      // would be changed.)
      insertTupleDataRange(dbPage, newTupleEnd, len);

      // Set the slot's value to be the starting offset of the tuple.
      // We have to do this *after* we insert the new space for the new
      // tuple, or else insertTupleDataRange() will clobber the
      // slot-value of this tuple.
      setSlotValue(dbPage, slot, newTupleStart);

      // Finally, return the slot-index of the new tuple.
      return slot;
  }


  public static void deleteTuple(DBPage dbPage, int slot) {

      if (slot < 0) {
          throw new IllegalArgumentException("Slot must be nonnegative; got " +
              slot);
      }

      int numSlots = getNumSlots(dbPage);

      if (slot >= numSlots) {
          throw new IllegalArgumentException("Page only has " + numSlots +
              " slots, but slot " + slot + " was requested for deletion.");
      }

      // Get the tuple's offset and length.
      int tupleStart = getSlotValue(dbPage, slot);
      if (tupleStart == EMPTY_SLOT) {
          throw new IllegalArgumentException("Slot " + slot +
              " was requested for deletion, but it is already deleted.");
      }

      int tupleLength = getTupleLength(dbPage, slot);

      // Mark the slot's entry as empty, and clear out the tuple's space.

      logger.debug(String.format(
          "Deleting tuple page %d, slot %d with starting offset %d, length %d.",
          dbPage.getPageNo(), slot, tupleStart, tupleLength));

      deleteTupleDataRange(dbPage, tupleStart, tupleLength);
      setSlotValue(dbPage, slot, EMPTY_SLOT);

      // Finally, release all empty slots at the end of the slot-list.

      boolean numSlotsChanged = false;
      for (slot = numSlots - 1; slot >= 0; slot--) {
          // currSlotValue is either the start of that slot's tuple-data,
          // or it is set to EMPTY_SLOT.
          int currSlotValue = getSlotValue(dbPage, slot);

          if (currSlotValue != EMPTY_SLOT)
              break;

          numSlots--;
          numSlotsChanged = true;
      }

      if (numSlotsChanged)
          setNumSlots(dbPage, numSlots);
    }
}