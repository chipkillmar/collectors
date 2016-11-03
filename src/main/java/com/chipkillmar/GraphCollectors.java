package com.chipkillmar;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Outputs a DOT graph showing how HotSpot JVM GC command line options map to different
 * collector selections in the VM at runtime.
 * <p>
 * It creates combinations of GC options, and uses them to construct a command line
 * for starting an external JVM process that will print actual collector settings to stdout.
 */
public class GraphCollectors {
    public static void main(String[] args) {
        // These are the GC command line options available in the HotSpot JVM.
        // Generate the power set from all available collector positive and negative command line options.
        Set<Set<String>> powerSet = Sets.powerSet(new LinkedHashSet<>(Arrays.asList(
                "-XX:+UseConcMarkSweepGC",
                "-XX:+UseG1GC",
                "-XX:+UseParNewGC",
                "-XX:+UseParallelGC",
                "-XX:+UseParallelOldGC",
                "-XX:+UseSerialGC",
                "-XX:-UseConcMarkSweepGC",
                "-XX:-UseG1GC",
                "-XX:-UseParNewGC",
                "-XX:-UseParallelGC",
                "-XX:-UseParallelOldGC",
                "-XX:-UseSerialGC"
        )));

        // Filter in combinations of 1 or 2 command line options.
        List<Set<String>> collectorOptions = powerSet.stream()
                .filter(s -> s.size() == 1 || s.size() == 2)
                .collect(Collectors.toList());

        // Filter out combinations of all negative (-XX:-...) command line options.
        collectorOptions = collectorOptions.stream().filter(options -> {
            final boolean[] allNegative = {true};
            options.forEach(option -> {
                if (!isNegativeOption(option)) {
                    allNegative[0] = false;
                }
            });
            return !allNegative[0];
        }).collect(Collectors.toList());

        // Filter out combinations of negating (-XX:-UseG1GC -XX:+UseG1GC) command line options.
        collectorOptions = collectorOptions.stream().filter(options -> {
            if (options.size() == 2) {
                Iterator<String> iterator = options.iterator();
                String option1 = iterator.next();
                String option2 = iterator.next();
                return !option1.substring("-XX:-".length()).equals(option2.substring("-XX:+".length()));
            }
            return true;
        }).collect(Collectors.toList());

        // Build the command to start the JVM.
        String javaHome = System.getProperty("java.home");
        String fileSeparator = System.getProperty("file.separator");
        String javaCommand = String.format("%s%sbin%sjava", javaHome, fileSeparator, fileSeparator);

        // The fully qualified Java main class to execute.
        String mainClass = PrintGCMXBeanNames.class.getName();

        // Maps JVM options to java commands that will invoke a program to print the collector
        // names via JMX.
        Map<Set<String>, List<String>> commandMap = Maps.newLinkedHashMap();
        collectorOptions.forEach(options -> {
            List<String> command = Lists.newArrayList();
            command.add(javaCommand);
            options.forEach(command::add);
            command.add("-classpath");
            command.add(System.getProperty("java.class.path"));
            command.add(mainClass);
            commandMap.put(options, command);
        });

        // Maps JVM options to runtime collector names.
        final Map<Set<String>, List<String>> optionsMap = Maps.newLinkedHashMap();
        commandMap.forEach((options, command) -> {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            try {
                Process process = processBuilder.start();
                int returnCode = process.waitFor();
                if (returnCode == 0) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    int b;
                    while (-1 != (b = process.getInputStream().read())) {
                        baos.write(b);
                    }
                    List<String> collectorNames = Splitter.on(',').splitToList(baos.toString("UTF-8"));
                    optionsMap.put(options, collectorNames);
                }
                // Return code of 1 means the following:
                // "Conflicting collector combinations in option list; please refer to the release notes for the
                // combinations allowed
                // Error: Could not create the Java Virtual Machine.
                // Error: A fatal exception has occurred. Program will exit."
            } catch (Exception e) {
                System.err.println("An unexpected error occurred.");
                e.printStackTrace();
            }
        });

        // Removes sets with negative command line options that don't result in different collectors at runtime.
        // For example: "java -XX:+UseG1GC -XX:-UseSerialGC" results in the same collectors as "java -XX:+UseG1GC",
        // so we can remove the former options.
        Map<Set<String>, List<String>> filteredOptionsMap = optionsMap.entrySet().stream().filter(entry -> {
            Set<String> options = entry.getKey();
            if (options.size() == 2) {
                // Modify the set, removing the negative options.
                Set<String> modifiedOptions = Sets.newLinkedHashSet();
                modifiedOptions.addAll(options.stream()
                        .filter(option -> !isNegativeOption(option))
                        .collect(Collectors.toList())
                );
                // Removes sets with negative options that don't affect the results.
                return modifiedOptions.size() == 1 && !optionsMap.get(modifiedOptions).equals(optionsMap.get(options));
            } else {
                return true;
            }
        }).collect(Collectors.toMap(Entry::getKey, Entry::getValue));

        // Prints a DOT directed graph description of JVM collector options to collector names.
        System.out.println("digraph {");
        System.out.println("\tlabel=\"HotSpot JVM Collector Options\";");
        System.out.println("\tlabelloc=top;");
        System.out.println("\trankdir=LR;");
        filteredOptionsMap.forEach((options, names) ->
                System.out.printf("\t\"%s\" -> \"%s\"\n", Joiner.on(' ').join(options), Joiner.on(", ").join(names))
        );
        System.out.println("}");
    }

    private static boolean isNegativeOption(String option) {
        return Strings.nullToEmpty(option).startsWith("-XX:-");
    }
}
