package fuberlin.offloadingchess.offloading;


import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;


/**
 * Created by christianbruns on 23.06.15.
 *
 * This class provides statical methods for serialize and deserialize java objects
 *
 */
public class Serializer {


    /**
     * serialize a java object
     * @param toSerialize to serialize object
     * @return            serialized object
     */
    public static String serialize(Object toSerialize) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(toSerialize);
            byte[] byteArray = bos.toByteArray();
            String encodedObject = Base64.encodeToString(byteArray, Base64.DEFAULT).replaceAll("(?:\\r\\n|\\n\\r|\\n|\\r)", "");
            bos.close();
            oos.close();
            return encodedObject;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    /**
     * desirialize a java object
     * @param toDeserialize to desirialize object
     * @return              the original java object
     */
    public static Object deserialize(String toDeserialize) {
        byte[] content = Base64.decode(toDeserialize, Base64.DEFAULT);
        ObjectInputStream oistream;
        try {
            oistream = new ObjectInputStream(new ByteArrayInputStream(content));
            Object obj = oistream.readObject();
            oistream.close();
            return obj;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }
}
