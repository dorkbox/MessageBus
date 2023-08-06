module dorkbox.MessageBus {
    exports dorkbox.messageBus;

    requires transitive dorkbox.classUtils;
    requires transitive dorkbox.collections;
    requires transitive dorkbox.updates;
    requires transitive dorkbox.utilities;

    requires transitive com.conversantmedia.disruptor;
    requires transitive com.lmax.disruptor;
    requires transitive org.objectweb.asm;
    requires transitive com.esotericsoftware.reflectasm;

    requires transitive org.slf4j;

    requires transitive kotlin.stdlib;
}
