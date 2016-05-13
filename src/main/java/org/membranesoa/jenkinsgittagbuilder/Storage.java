package org.membranesoa.jenkinsgittagbuilder;

import hudson.FilePath;

import java.io.*;
import java.util.HashSet;
import java.util.Set;

public class Storage {

    FilePath knownTags;
    private FilePath workspace;

    public Storage(FilePath workspace) {
        knownTags = new FilePath(workspace, "known-tags.txt");

        this.workspace = workspace;
    }

    /**
     * @param allTags
     * @return all newly discovered tags (compared to the last invocation)
     */
    public Set<String> storeNewTags(Set<String> allTags) throws IOException, InterruptedException {
        if (allTags.isEmpty())
            return allTags;

        synchronized (Storage.class) {

            HashSet<String> old = new HashSet<String>();
            if (knownTags.exists()) {
                try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(knownTags.read(), "UTF-8"))) {
                    while (true) {
                        String line = bufferedReader.readLine();
                        if (line == null)
                            break;
                        else
                            old.add(line);
                    }
                }

                for (String tag : old)
                    allTags.remove(tag);
                for (String tag : allTags)
                    old.add(tag);
            } else {
                old.addAll(allTags);
                allTags.clear();
            }

            try (OutputStreamWriter fr = new OutputStreamWriter(knownTags.write(), "UTF-8")) {
                for (String tag : old)
                    fr.write(tag + System.lineSeparator());
            }

        }
        return allTags;
    }
}
