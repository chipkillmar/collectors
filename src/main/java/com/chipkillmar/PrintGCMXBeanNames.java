package com.chipkillmar;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * A main class that simply prints the names of the garbage collectors in use by the JVM, as
 * determined by {@link ManagementFactory#getGarbageCollectorMXBeans()}.
 */
public class PrintGCMXBeanNames {
    public static void main(String[] args) {
        List<String> collectorNames = Lists.newArrayList();
        ManagementFactory.getGarbageCollectorMXBeans().forEach(mxBean -> collectorNames.add(mxBean.getName()));
        System.out.print(Joiner.on(',').join(collectorNames));
    }
}
