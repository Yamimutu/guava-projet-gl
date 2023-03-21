package com.google.common.collect;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.AbstractCollection;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Map;
import java.util.Spliterator;

import javax.annotation.CheckForNull;

import org.checkerframework.checker.units.qual.K;

import com.google.j2objc.annotations.WeakOuter;

/**
 * Collection decorator that stays in sync with the multimap values for a key. There are two kinds
 * of wrapped collections: full and subcollections. Both have a delegate pointing to the
 * underlying collection class.
 *
 * <p>Full collections, identified by a null ancestor field, contain all multimap values for a
 * given key. Its delegate is a value in {@link AbstractMapBasedMultimap#map} whenever the
 * delegate is non-empty. The {@code refreshIfEmpty}, {@code removeIfEmpty}, and {@code addToMap}
 * methods ensure that the {@code WrappedCollection} and map remain consistent.
 *
 * <p>A subcollection, such as a sublist, contains some of the values for a given key. Its
 * ancestor field points to the full wrapped collection with all values for the key. The
 * subcollection {@code refreshIfEmpty}, {@code removeIfEmpty}, and {@code addToMap} methods call
 * the corresponding methods of the full wrapped collection.
 */
@WeakOuter
public class WrappedCollection<V> extends AbstractCollection<V> {
  @ParametricNullness final K key;
  Collection<V> delegate;
  @CheckForNull final WrappedCollection ancestor;
  @CheckForNull final Collection<V> ancestorDelegate;
  private transient Map<K, Collection<V>> map;
  private transient int totalSize;

  WrappedCollection(
      @ParametricNullness K key,
      Collection<V> delegate,
      @CheckForNull WrappedCollection ancestor,
      Map<K, Collection<V>> map) {
    this.key = key;
    this.delegate = delegate;
    this.ancestor = ancestor;
    this.ancestorDelegate = (ancestor == null) ? null : ancestor.getDelegate();
  }

  /**
   * If the delegate collection is empty, but the multimap has values for the key, replace the
   * delegate with the new collection for the key.
   *
   * <p>For a subcollection, refresh its ancestor and validate that the ancestor delegate hasn't
   * changed.
   */
  void refreshIfEmpty() {
    if (ancestor != null) {
      ancestor.refreshIfEmpty();
      if (ancestor.getDelegate() != ancestorDelegate) {
        throw new ConcurrentModificationException();
      }
    } else if (delegate.isEmpty()) {
      Collection<V> newDelegate = map.get(key);
      if (newDelegate != null) {
        delegate = newDelegate;
      }
    }
  }

  /**
   * If collection is empty, remove it from {@code AbstractMapBasedMultimap.this.map}. For
   * subcollections, check whether the ancestor collection is empty.
   */
  void removeIfEmpty() {
    if (ancestor != null) {
      ancestor.removeIfEmpty();
    } else if (delegate.isEmpty()) {
      map.remove(key);
    }
  }

  @ParametricNullness
  K getKey() {
    return key;
  }

  /**
   * Add the delegate to the map. Other {@code WrappedCollection} methods should call this method
   * after adding elements to a previously empty collection.
   *
   * <p>Subcollection add the ancestor's delegate instead.
   */
  void addToMap() {
    if (ancestor != null) {
      ancestor.addToMap();
    } else {
      map.put(key, delegate);
    }
  }

  @Override
  public int size() {
    refreshIfEmpty();
    return delegate.size();
  }

  @Override
  public boolean equals(@CheckForNull Object object) {
    if (object == this) {
      return true;
    }
    refreshIfEmpty();
    return delegate.equals(object);
  }

  @Override
  public int hashCode() {
    refreshIfEmpty();
    return delegate.hashCode();
  }

  @Override
  public String toString() {
    refreshIfEmpty();
    return delegate.toString();
  }

  Collection<V> getDelegate() {
    return delegate;
  }

  @Override
  public Iterator<V> iterator() {
    refreshIfEmpty();
    return new WrappedIterator();
  }

  @Override
  public Spliterator<V> spliterator() {
    refreshIfEmpty();
    return delegate.spliterator();
  }

  /** Collection iterator for {@code WrappedCollection}. */
  class WrappedIterator implements Iterator<V> {
    final Iterator<V> delegateIterator;
    final Collection<V> originalDelegate = delegate;

    WrappedIterator() {
      delegateIterator = iteratorOrListIterator(delegate);
    }

    WrappedIterator(Iterator<V> delegateIterator) {
      this.delegateIterator = delegateIterator;
    }

    /**
     * If the delegate changed since the iterator was created, the iterator is no longer valid.
     */
    void validateIterator() {
      refreshIfEmpty();
      if (delegate != originalDelegate) {
        throw new ConcurrentModificationException();
      }
    }

    @Override
    public boolean hasNext() {
      validateIterator();
      return delegateIterator.hasNext();
    }

    @Override
    @ParametricNullness
    public V next() {
      validateIterator();
      return delegateIterator.next();
    }

    @Override
    public void remove() {
      delegateIterator.remove();
      totalSize--;
      removeIfEmpty();
    }

    Iterator<V> getDelegateIterator() {
      validateIterator();
      return delegateIterator;
    }
  }

  @Override
  public boolean add(@ParametricNullness V value) {
    refreshIfEmpty();
    boolean wasEmpty = delegate.isEmpty();
    boolean changed = delegate.add(value);
    if (changed) {
      totalSize++;
      if (wasEmpty) {
        addToMap();
      }
    }
    return changed;
  }

  @CheckForNull
  WrappedCollection getAncestor() {
    return ancestor;
  }

  // The following methods are provided for better performance.

  @Override
  public boolean addAll(Collection<? extends V> collection) {
    if (collection.isEmpty()) {
      return false;
    }
    int oldSize = size(); // calls refreshIfEmpty
    boolean changed = delegate.addAll(collection);
    if (changed) {
      int newSize = delegate.size();
      totalSize += (newSize - oldSize);
      if (oldSize == 0) {
        addToMap();
      }
    }
    return changed;
  }

  @Override
  public boolean contains(@CheckForNull Object o) {
    refreshIfEmpty();
    return delegate.contains(o);
  }

  @Override
  public boolean containsAll(Collection<?> c) {
    refreshIfEmpty();
    return delegate.containsAll(c);
  }

  @Override
  public void clear() {
    int oldSize = size(); // calls refreshIfEmpty
    if (oldSize == 0) {
      return;
    }
    delegate.clear();
    totalSize -= oldSize;
    removeIfEmpty(); // maybe shouldn't be removed if this is a sublist
  }

  @Override
  public boolean remove(@CheckForNull Object o) {
    refreshIfEmpty();
    boolean changed = delegate.remove(o);
    if (changed) {
      totalSize--;
      removeIfEmpty();
    }
    return changed;
  }

  @Override
  public boolean removeAll(Collection<?> c) {
    if (c.isEmpty()) {
      return false;
    }
    int oldSize = size(); // calls refreshIfEmpty
    boolean changed = delegate.removeAll(c);
    if (changed) {
      int newSize = delegate.size();
      totalSize += (newSize - oldSize);
      removeIfEmpty();
    }
    return changed;
  }

  @Override
  public boolean retainAll(Collection<?> c) {
    checkNotNull(c);
    int oldSize = size(); // calls refreshIfEmpty
    boolean changed = delegate.retainAll(c);
    if (changed) {
      int newSize = delegate.size();
      totalSize += (newSize - oldSize);
      removeIfEmpty();
    }
    return changed;
  }
}