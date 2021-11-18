// FULL_JDK

public interface ImmutableCollection<out E>: Collection<E>

public interface ImmutableSet<out E>: Set<E>, ImmutableCollection<E>

public interface ImmutableMap<K, out V>: Map<K, V> {

    override val keys: ImmutableSet<K>

    override val values: ImmutableCollection<V>

    override val entries: ImmutableSet<Map.Entry<K, V>>
}

public interface PersistentMap<K, out V> : ImmutableMap<K, V> {
    fun put(key: K, value: @UnsafeVariance V): PersistentMap<K, V>

    fun remove(key: K): PersistentMap<K, V>

    fun remove(key: K, value: @UnsafeVariance V): PersistentMap<K, V>

    fun putAll(m: Map<out K, @UnsafeVariance V>): PersistentMap<K, V>

    fun clear(): PersistentMap<K, V>
}

abstract class ControlFlowInfo<S : ControlFlowInfo<S, K, V>, K : Any, V : Any> protected constructor(
    protected val map: PersistentMap<K, V>,
) : PersistentMap<K, V> by map {

    protected abstract val constructor: (PersistentMap<K, V>) -> S

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

    override fun put(key: K, value: V): S {
        return constructor(map.put(key, value))
    }

    override fun remove(key: K): S {
        return constructor(map.remove(key))
    }

    abstract fun merge(other: S): S
}

class EdgeLabel

abstract class PathAwareControlFlowInfo<P : PathAwareControlFlowInfo<P, S>, S : ControlFlowInfo<S, *, *>>(
    map: PersistentMap<EdgeLabel, S>,
) : ControlFlowInfo<P, EdgeLabel, S>(map) {
    override fun merge(other: P): P = other
}

class EventOccurrencesRange

abstract class EventOccurrencesRangeInfo<E : EventOccurrencesRangeInfo<E, K>, K : Any>(
    map: PersistentMap<K, EventOccurrencesRange>
) : ControlFlowInfo<E, K, EventOccurrencesRange>(map)

abstract class PropertyInitializationInfo(
    map: PersistentMap<String, EventOccurrencesRange>
) : EventOccurrencesRangeInfo<PropertyInitializationInfo, String>(map)

class PathAwarePropertyInitializationInfo(
    map: PersistentMap<EdgeLabel, PropertyInitializationInfo>
) : PathAwareControlFlowInfo<PathAwarePropertyInitializationInfo, PropertyInitializationInfo>(map) {
    companion object {
        val EMPTY = PathAwarePropertyInitializationInfo(null!!)
    }

    override val constructor: (PersistentMap<EdgeLabel, PropertyInitializationInfo>) -> PathAwarePropertyInitializationInfo =
        ::PathAwarePropertyInitializationInfo

    override val empty: () -> PathAwarePropertyInitializationInfo =
        ::EMPTY

}
