package com.payneteasy.dcagent.modules.docker;

public class TextLinesBuilder {

    private final StringBuilder sb = new StringBuilder();

    public void addLines(String ... lines) {
        for (String line : lines) {
            addLine(line);
        }
    }

    public void addLine(String aLine) {
        sb.append(aLine).append("\n");
    }

    public void addLineConcat(String ... substrings) {
        for (String substring : substrings) {
            sb.append(substring);
        }
        sb.append("\n");
    }

    @Override
    public String toString() {
        return sb.toString();
    }

    public static class KeyValue {
        private final String key;
        private final String value;

        public KeyValue(String key, String value) {
            this.key   = key;
            this.value = value;
        }
    }
}
