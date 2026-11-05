package net.blocks168.pgecs

import net.blocks168.pgecs.db.TxScope
import net.blocks168.pgecs.dsl.QueryExecutor
import net.blocks168.pgecs.dsl.Queryable

/**
 * The receiver of `ecs.read { }`: one `SERIALIZABLE READ ONLY` transaction, with reads by entity and
 * queries (`query { }` here is the same as `ecs.query { }`).
 */
interface EcsRead : TxScope, Queryable {
    val ecs: Ecs

    /** Whether [entity] exists. */
    fun exists(entity: EntityId): Boolean

    /** The value of [component] on [entity], or null when it does not have it. */
    operator fun <T> get(entity: EntityId, component: Component<T>): T?

    /** Whether [entity] has [type], a tag or a value component. */
    fun has(entity: EntityId, type: ComponentType): Boolean

    /** Whether [entity] has the pair `(relation, target)`. */
    fun has(entity: EntityId, relation: Relation, target: EntityId): Boolean

    /** The targets of [entity]'s [relation] pairs, sorted. */
    fun targets(entity: EntityId, relation: Relation): List<EntityId>

    /** The target of [entity]'s exclusive [relation], or null. */
    fun target(entity: EntityId, relation: Relation): EntityId? {
        require(relation.exclusive) { "relation ${relation.name} is not exclusive; use targets" }
        return targets(entity, relation).firstOrNull()
    }
}

/**
 * The receiver of `ecs.tx { }`: one read-write `SERIALIZABLE` transaction.
 *
 * Writes are buffered and flushed sorted by entity id (so concurrent transactions lock rows in one
 * order), before every read or query of the transaction and at commit. Errors that Postgres raises
 * for a write (a unique index, a JSON Schema, a reserved entity) therefore surface at the next read,
 * query or [flush], or when the block returns; call [flush] to see them at a specific point.
 */
interface EcsTx : EcsRead {
    /** Creates an entity with a new UUIDv7 id and writes [block] to it. */
    fun create(block: EntityWriter.() -> Unit = {}): EntityId

    /** Creates the entity [id] (it must not exist) and writes [block] to it. */
    fun create(id: EntityId, block: EntityWriter.() -> Unit = {}): EntityId

    /** Creates [id] unless it exists, then writes [block] to it: for entities with stable ids. */
    fun ensure(id: EntityId, block: EntityWriter.() -> Unit = {}): EntityId

    /** Writes [block] to the existing [entity]. */
    fun update(entity: EntityId, block: EntityWriter.() -> Unit)

    /** Deletes [entity] and its components; cleanup policies apply (children of it are deleted too). */
    fun delete(entity: EntityId)

    /** Sends the buffered writes to Postgres now. */
    fun flush()
}

internal open class EcsReadImpl(
    final override val ecs: Ecs,
    protected val scope: TxScope,
) : EcsRead, TxScope by scope {
    override val queryExecutor: QueryExecutor get() = ecs.queryExecutor

    protected open fun beforeRead() {}

    override fun exists(entity: EntityId): Boolean {
        beforeRead()
        return Sql.exists(connection, "select 1 from ecs_entity where id = ?", entity.id)
    }

    override fun <T> get(entity: EntityId, component: Component<T>): T? {
        ecs.registry.require(component)
        beforeRead()
        val json = Sql.string(
            connection,
            "select value::text from ecs_component where entity = ? and rel = ? and target = '00000000-0000-0000-0000-000000000000'",
            entity.id, component.name,
        ) ?: return null
        return Values.decode(component, json)
    }

    override fun has(entity: EntityId, type: ComponentType): Boolean {
        require(type !is Relation) { "relation ${type.name} needs a target: has(entity, relation, target) or targets(entity, relation)" }
        require(type !is Capability<*>) { "capability ${type.name} is not a component" }
        ecs.registry.require(type)
        beforeRead()
        return Sql.exists(
            connection,
            "select 1 from ecs_component where entity = ? and rel = ? and target = '00000000-0000-0000-0000-000000000000'",
            entity.id, type.name,
        )
    }

    override fun has(entity: EntityId, relation: Relation, target: EntityId): Boolean {
        ecs.registry.require(relation)
        beforeRead()
        return Sql.exists(
            connection,
            "select 1 from ecs_component where entity = ? and rel = ? and target = ?",
            entity.id, relation.name, target.id,
        )
    }

    override fun targets(entity: EntityId, relation: Relation): List<EntityId> {
        ecs.registry.require(relation)
        beforeRead()
        return Sql.uuids(
            connection,
            "select target from ecs_component where entity = ? and rel = ? order by target",
            entity.id, relation.name,
        ).map(EntityId::of)
    }
}

internal class EcsTxImpl(ecs: Ecs, scope: TxScope, private val buffer: WriteBuffer) : EcsReadImpl(ecs, scope), EcsTx {
    override fun beforeRead() = flush()

    override fun create(block: EntityWriter.() -> Unit): EntityId = create(EntityId.of(Uuid7.next()), block)

    override fun create(id: EntityId, block: EntityWriter.() -> Unit): EntityId {
        require(!id.isVariable) { "cannot create a query variable" }
        buffer.create(id.id, ifMissing = false)
        Writer(id).block()
        return id
    }

    override fun ensure(id: EntityId, block: EntityWriter.() -> Unit): EntityId {
        require(!id.isVariable) { "cannot create a query variable" }
        buffer.create(id.id, ifMissing = true)
        Writer(id).block()
        return id
    }

    override fun update(entity: EntityId, block: EntityWriter.() -> Unit) {
        require(!entity.isVariable) { "cannot update a query variable" }
        Writer(entity).block()
    }

    override fun delete(entity: EntityId) {
        require(!entity.isVariable) { "cannot delete a query variable" }
        buffer.delete(entity.id)
    }

    override fun flush() {
        scope.connection // checks that the scope is still usable
        buffer.flush(connection)
    }

    override fun <T> savepoint(block: () -> T): T {
        flush()
        return scope.savepoint {
            try {
                block().also { flush() }
            } catch (t: Throwable) {
                // everything buffered now was written inside the savepoint, which is rolled back
                buffer.clear()
                throw t
            }
        }
    }

    private inner class Writer(override val entity: EntityId) : EntityWriter {
        private val id get() = entity.id

        override fun <T> set(component: Component<T>, value: T?) {
            ecs.registry.require(component)
            if (value == null) {
                buffer.remove(id, component.name, WriteBuffer.NIL)
                return
            }
            component.check(value)
            buffer.set(id, component.name, WriteBuffer.NIL, Values.encode(component, value), merge = component.mergesOnWrite, exclusive = false)
        }

        override fun <T> get(component: Component<T>): T? = this@EcsTxImpl.get(entity, component)

        override fun add(tag: Tag) {
            ecs.registry.require(tag)
            buffer.set(id, tag.name, WriteBuffer.NIL, null, merge = false, exclusive = false)
        }

        override fun remove(tag: Tag) {
            ecs.registry.require(tag)
            buffer.remove(id, tag.name, WriteBuffer.NIL)
        }

        override fun add(relation: Relation, target: EntityId) {
            ecs.registry.require(relation)
            require(!target.isVariable) { "the target of a pair must be a real entity, not a query variable" }
            buffer.set(id, relation.name, target.id, null, merge = false, exclusive = relation.exclusive)
        }

        override fun remove(relation: Relation, target: EntityId) {
            ecs.registry.require(relation)
            buffer.remove(id, relation.name, target.id)
        }

        override fun removeAll(relation: Relation) {
            ecs.registry.require(relation)
            buffer.removeAll(id, relation.name)
        }
    }
}
