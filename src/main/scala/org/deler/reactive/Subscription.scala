package org.deler.reactive

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents a subscription that can be cancelled by using the `close` method. Closing a subscription
 * allows the [[org.deler.reactive.Observable]] or [[org.deler.reactive.Scheduler]] to clean up any resources and
 * cancel future notifications or scheduled actions.
 *
 * Subscriptions must be idempotent (so `close` can be called multiple times without ill effects).
 *
 * Subscriptions must be thread-safe.
 */
trait Subscription {

  /**
   * Close (or cancel) the subscription. This operation is idempotent.
   */
  def close()
}

/**
 * A simple subscription that does not do anything when closed.
 */
object NullSubscription extends Subscription {
  def close() = {}
}

/**
 * A subscription that is marked as `closed` when `close` is invoked.
 */
class BooleanSubscription extends Subscription {
  @volatile private var _closed = false

  def closed: Boolean = _closed;

  def close() {
    _closed = true
  }
}

/**
 * A subscription that executes `action` when closed for the first time.
 */
class ActionSubscription(action: () => Unit) extends Subscription {
  private val _closed = new AtomicBoolean(false)

  def close() {
    if (_closed.compareAndSet(false, true)) {
      action()
    }
  }
}

/**
 * A subscription that (optionally) contains another subscription. When this MutableSubscription is closed the
 * contained subscription is also closed. Any future replacements will also be closed automatically.
 *
 * When the contained subscription is replaced with a new subscription the contained subscription is first closed.
 */
class MutableSubscription(initial: Option[Subscription] = None) extends Subscription {
  private var _subscription: Option[Subscription] = initial
  private var _closed = false

  def this(subscription: Subscription) = this (Some(subscription))

  def clear(): Unit = synchronized {
    _subscription foreach {_.close()}
    _subscription = None
  }

  def clearAndSet(delegate: => Subscription): Unit = synchronized {
    if (_closed)
      return

    clear()
    set(delegate) // TODO: unlock 'this' while invoking delegate?
  }

  def set(subscription: Subscription): Unit = synchronized {
    if (_closed) {
      subscription.close()
    } else {
      _subscription foreach {_.close()}
      _subscription = Some(subscription)
    }
  }

  def close(): Unit = synchronized {
    if (_closed)
      return

    _closed = true
    clear()
  }
}

/**
 * A subscription that contains other subscriptions. When this CompositeSubscription is closed, all contained
 * subscriptions are also closed. A closed CompositeSubscription will also automatically close any new subscriptions
 * that are added.
 */
class CompositeSubscription(initial: Subscription*) extends Subscription {
  import scala.collection._

  private var _closed = false
  private val _subscriptions = mutable.Set[Subscription](initial: _*)

  /**
   * Adds the `subscription` to this CompositeSubscription or closes the `subscription` if this CompositeSubscription
   * is already closed.
   */
  def add(subscription: Subscription) = synchronized {
    if (_closed) {
      subscription.close()
    } else {
      _subscriptions add subscription
    }
  }

  /**
   * Removes and closes the `subscription`. Does nothing when the `subscription` is not part of this
   * CompositeSubscription.
   */
  def remove(subscription: Subscription): Unit = synchronized {
    if (_subscriptions remove subscription) {
      subscription.close()
    }
  }

  /**
   * Tests if this CompositeSubscription is empty.
   *
   * @return `true` if there is no subscription in this CompositeSubscription, `false` otherwise.
   */
  def isEmpty: Boolean = synchronized {_subscriptions.isEmpty}

  /**
   * Closes all contained Subscriptions and removes them from this CompositeSubscription.
   */
  def close(): Unit = synchronized {
    if (_closed)
      return

    _closed = true
    _subscriptions foreach {_.close()}
    _subscriptions.clear()
  }
}
