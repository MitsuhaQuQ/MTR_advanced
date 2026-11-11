package mtr.data;

/** One bounded, ordered logical edit. Partial transfers never expose bytes to the edit handler. */
public final class EditTransferAssembler {
	public static final int CHUNK_BYTES = 24 * 1024;
	public static final int MAX_BYTES = 1024 * 1024;
	public static final long TIMEOUT_MILLIS = 30000;
	private byte[] buffer;
	private long id, updated;
	private int kind, received;
	public byte[] accept(long transferId, int transferKind, int total, int offset, byte[] bytes, long now) {
		if (total <= 0 || total > MAX_BYTES || bytes.length == 0 || bytes.length > CHUNK_BYTES || offset < 0 || (long) offset + bytes.length > total) {
			clear(); throw new IllegalArgumentException("Invalid edit fragment size");
		}
		if (buffer != null && now - updated > TIMEOUT_MILLIS) clear();
		if (offset == 0) {
			buffer = new byte[total]; id = transferId; kind = transferKind; received = 0;
		}
		if (buffer == null || id != transferId || kind != transferKind || buffer.length != total || received != offset) {
			clear(); throw new IllegalArgumentException("Out-of-order edit fragment");
		}
		System.arraycopy(bytes, 0, buffer, offset, bytes.length);
		received += bytes.length; updated = now;
		if (received != total) return null;
		byte[] complete = buffer; clear(); return complete;
	}
	public boolean expired(long now) { return buffer == null || now - updated > TIMEOUT_MILLIS; }
	public void clear() { buffer = null; received = 0; }
}
