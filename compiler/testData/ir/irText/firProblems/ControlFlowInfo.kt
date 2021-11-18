// FULL_JDK

abstract class ControlFlowInfo<S : ControlFlowInfo<S, K, V>, K : Any, V : Any> protected constructor(
    protected val map: Map<K, V>,
) : Map<K, V> by map {

    protected abstract val constructor: (Map<K, V>) -> S

    protected abstract val empty: () -> S

    override fun equals(other: Any?): Boolean {
        return map == (other as? ControlFlowInfo<*, *, *>)?.map
    }

    override fun hashCode(): Int {
        return map.hashCode()
    }

    override fun toString(): String {
        return map.toString()
    }

//    override fun put(key: K, value: V): S {
//        return constructor(map.put(key, value))
//    }

//    override fun remove(key: K): S {
//        return constructor(map.remove(key))
//    }

    abstract fun merge(other: S): S
}

class EdgeLabel

abstract class PathAwareControlFlowInfo<P : PathAwareControlFlowInfo<P, S>, S : ControlFlowInfo<S, *, *>>(
    map: Map<EdgeLabel, S>,
) : ControlFlowInfo<P, EdgeLabel, S>(map) {
    override fun merge(other: P): P = other
}

class EventOccurrencesRange

abstract class EventOccurrencesRangeInfo<E : EventOccurrencesRangeInfo<E, K>, K : Any>(
    map: Map<K, EventOccurrencesRange>
) : ControlFlowInfo<E, K, EventOccurrencesRange>(map)

abstract class PropertyInitializationInfo(
    map: Map<String, EventOccurrencesRange>
) : EventOccurrencesRangeInfo<PropertyInitializationInfo, String>(map)

class PathAwarePropertyInitializationInfo(
    map: Map<EdgeLabel, PropertyInitializationInfo>
) : PathAwareControlFlowInfo<PathAwarePropertyInitializationInfo, PropertyInitializationInfo>(map) {
    companion object {
        val EMPTY = PathAwarePropertyInitializationInfo(null!!)
    }

    override val constructor: (Map<EdgeLabel, PropertyInitializationInfo>) -> PathAwarePropertyInitializationInfo =
        ::PathAwarePropertyInitializationInfo

    override val empty: () -> PathAwarePropertyInitializationInfo =
        ::EMPTY

}
