package org.nustaq.sometest;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by ruedi on 05.07.17.
 */
public class TestPojo implements Serializable {
    Map aMap = new HashMap();
    List<String> strings = new ArrayList<>();

    public TestPojo() {
        aMap.put("A",1);
        aMap.put("B",1);
        strings.add("1");
        strings.add("2");
        strings.add("3");strings.add("4");
    }
}
