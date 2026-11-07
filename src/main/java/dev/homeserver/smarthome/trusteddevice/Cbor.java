package dev.homeserver.smarthome.trusteddevice;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small CBOR reader, just enough for WebAuthn: unsigned and negative integers, byte and text strings, arrays, maps,
 * booleans and null. Anything else (floats, tags, indefinite lengths) is refused, as is nesting beyond a few levels.
 */
final class Cbor {

	private static final int MAX_DEPTH = 8;

	private final byte[] data;
	private int position;

	private Cbor(byte[] data, int position) {
		this.data = data;
		this.position = position;
	}

	/** The item that starts at {@code offset}; with {@code end} (one element) the offset after it is stored there. */
	static Object read(byte[] data, int offset, int[] end) {
		var reader = new Cbor(data, offset);
		Object value = reader.item(0);
		if (end != null) {
			end[0] = reader.position;
		}
		return value;
	}

	static Object read(byte[] data) {
		int[] end = new int[1];
		Object value = read(data, 0, end);
		if (end[0] != data.length) {
			throw new IllegalArgumentException("trailing bytes after CBOR item");
		}
		return value;
	}

	private Object item(int depth) {
		if (depth > MAX_DEPTH) {
			throw new IllegalArgumentException("CBOR nested too deeply");
		}
		int initial = next();
		int major = initial >> 5;
		int info = initial & 0x1f;
		switch (major) {
			case 0:
				return argument(info);
			case 1:
				return -1 - argument(info);
			case 2:
				return take(length(info));
			case 3:
				return new String(take(length(info)), StandardCharsets.UTF_8);
			case 4: {
				int size = length(info);
				List<Object> list = new ArrayList<>();
				for (int i = 0; i < size; i++) {
					list.add(item(depth + 1));
				}
				return list;
			}
			case 5: {
				int size = length(info);
				Map<Object, Object> map = new LinkedHashMap<>();
				for (int i = 0; i < size; i++) {
					Object key = item(depth + 1);
					map.put(key, item(depth + 1));
				}
				return map;
			}
			case 7:
				if (info == 20) {
					return Boolean.FALSE;
				}
				if (info == 21) {
					return Boolean.TRUE;
				}
				if (info == 22) {
					return null;
				}
				throw new IllegalArgumentException("unsupported CBOR simple value");
			default:
				throw new IllegalArgumentException("unsupported CBOR type");
		}
	}

	private int next() {
		if (position >= data.length) {
			throw new IllegalArgumentException("CBOR ends too early");
		}
		return data[position++] & 0xff;
	}

	private long argument(int info) {
		if (info < 24) {
			return info;
		}
		int bytes = switch (info) {
			case 24 -> 1;
			case 25 -> 2;
			case 26 -> 4;
			case 27 -> 8;
			default -> throw new IllegalArgumentException("unsupported CBOR length");
		};
		long value = 0;
		for (int i = 0; i < bytes; i++) {
			value = (value << 8) | next();
		}
		if (value < 0) {
			throw new IllegalArgumentException("CBOR number too large");
		}
		return value;
	}

	private int length(int info) {
		long value = argument(info);
		if (value > data.length) {
			throw new IllegalArgumentException("CBOR length exceeds the data");
		}
		return (int) value;
	}

	private byte[] take(int count) {
		if (count > data.length - position) {
			throw new IllegalArgumentException("CBOR ends too early");
		}
		byte[] out = Arrays.copyOfRange(data, position, position + count);
		position += count;
		return out;
	}

}
