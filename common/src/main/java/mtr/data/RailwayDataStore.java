package mtr.data;

import java.util.function.BiConsumer;

public interface RailwayDataStore {

	void forEach(String type, BiConsumer<Long, byte[]> consumer) throws Exception;

	void write(String type, long id, byte[] data) throws Exception;

	void delete(String type, long id) throws Exception;

	void commit() throws Exception;

	void backup();

	void close() throws Exception;
}
