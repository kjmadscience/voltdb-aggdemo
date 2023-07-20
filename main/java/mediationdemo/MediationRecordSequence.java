package mediationdemo;

/* This file is part of VoltDB.
 * Copyright (C) 2008-2021 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */


import java.util.BitSet;

import org.voltdb.aggdemo.MediationSession;

/**
 * This is a BitSet where each Bit in each byte represents a possible sequence number
 * for a Mediation Session. 
 * 
 * If you haven't worked with BitSet before https://www.baeldung.com/java-bitset
 * is a useful reference.
 *
 */
public class MediationRecordSequence {

	BitSet theBitSet;

	/**
	 * Create a Bitset from an existing byte array. If it's null create
	 * one with MediationSession.MAX_POSSIBLE_SEQNO entries.
	 * @param rawData
	 */
	public MediationRecordSequence(byte[] rawData) {

		if (rawData == null) {
			theBitSet = new BitSet(MediationSession.MAX_POSSIBLE_SEQNO);
		} else {
			theBitSet = BitSet.valueOf(rawData);
		}

	}

	/**
	 * Mark seqno as having been seen.
	 * 
	 * @param seqno
	 */
	public void setSeqno(int seqno) {
		theBitSet.set(seqno);
	}

	/**
	 * See if a seqno has been seen.
	 * @param seqno
	 * @return true if 'seqno' has been seen, otherwise false.
	 */
	public boolean getSeqno(int seqno) {
		return theBitSet.get(seqno);
	}

	/**
	 * See if we have a full range from zero to seqno. Useful
	 * for checking for missing records prior to aggregation.
	 * @param seqno
	 * @return
	 */
	public boolean weHaveFromZeroTo(int seqno) {

		for (int i = 0; i < seqno; i++) {
			if (!theBitSet.get(i)) {
				return false;
			}
		}

		return true;
	}

	/**
	 * Convert back to byte[] for storage.
	 * @return BitSet as byte[]
	 */
	public byte[] getSequence() {
		return theBitSet.toByteArray();
	}

	@Override
	public String toString() {
		
		StringBuffer allString = new StringBuffer(MediationSession.MAX_POSSIBLE_SEQNO+1);
		
		for (int i=0; i < theBitSet.length(); i++) {
			if (getSeqno(i)) {
				allString.append('X');
			} else {
				allString.append('_');
			}
		}
				
		return allString.toString();
	}

}
