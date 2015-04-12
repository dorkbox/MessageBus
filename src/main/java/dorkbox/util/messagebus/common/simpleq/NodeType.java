package dorkbox.util.messagebus.common.simpleq;
public class NodeType {
    public static final short FREE = 0;

    public static final short P_INIT = 1;
    public static final short P_DONE = 2;

    public static final short C_INIT = 1;
    public static final short C_DONE = 2;


    private NodeType() {}
}