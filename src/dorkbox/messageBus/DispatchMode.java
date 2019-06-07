package dorkbox.messageBus;

public
enum DispatchMode {
    /**
     * Will only publish to listeners with this exact message signature. This is the fastest
     */
    Exact,

    /**
     * Will publish to listeners with this exact message signature, as well as listeners that match the super class types signatures.
     */
    ExactWithSuperTypes,
}
