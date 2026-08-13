package com.carstream.app;

public final class ByteRange {
    public final long start;
    public final long end;

    public ByteRange(long start, long end) {
        this.start = start;
        this.end = end;
    }

    public long length() { return end - start + 1; }

    public static ByteRange parse(String header, long totalLength) {
        if (header == null || !header.startsWith("bytes=") || totalLength <= 0) return null;
        String value = header.substring(6).trim();
        if (value.contains(",")) throw new IllegalArgumentException("Multiple ranges are unsupported");
        int dash = value.indexOf('-');
        if (dash < 0) throw new IllegalArgumentException("Invalid byte range");
        String left = value.substring(0, dash).trim();
        String right = value.substring(dash + 1).trim();
        try {
            if (left.isEmpty()) {
                long suffix = Long.parseLong(right);
                if (suffix <= 0) throw new IllegalArgumentException("Invalid suffix range");
                long start = Math.max(0, totalLength - suffix);
                return new ByteRange(start, totalLength - 1);
            }
            long start = Long.parseLong(left);
            if (start < 0 || start >= totalLength) throw new IllegalArgumentException("Unsatisfiable range");
            long end = right.isEmpty() ? totalLength - 1 : Long.parseLong(right);
            if (end < start) throw new IllegalArgumentException("Invalid byte range");
            return new ByteRange(start, Math.min(end, totalLength - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid byte range", e);
        }
    }
}
