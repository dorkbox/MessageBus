package dorkbox.util.messagebus.common;

/**
 *
 */
public
class MultiClass implements Comparable<MultiClass> {
    private final int value;

    public
    MultiClass(int value) {
        this.value = value;
    }

    @Override
    public
    int compareTo(final MultiClass o) {
        if (value < o.value) {
            return -1;
        }
        else if (value == o.value) {
            return 0;
        }
        else {
            return 1;
        }
    }

    @Override
    public
    boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final MultiClass that = (MultiClass) o;

        return value == that.value;

    }

    @Override
    public
    int hashCode() {
        return value;
    }
}
