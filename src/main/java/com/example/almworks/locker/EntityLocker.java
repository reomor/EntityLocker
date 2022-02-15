package com.example.almworks.locker;

/**
 * The task is to create a reusable utility class that provides synchronization mechanism similar to row-level DB locking.
 * <p>
 * The class is supposed to be used by the components that are responsible for managing storage and caching of different
 * type of entities in the application.
 * EntityLocker itself does not deal with the entities, only with the IDs (primary keys) of the entities.
 * <p>
 *
 * @param <ID> identifier type
 */
public interface EntityLocker<ID> {

  /**
   * Lock entity with certain id
   *
   * @param entityId entity identifier
   * @return result of locking
   */
  boolean lock(ID entityId) throws InterruptedException;

  /**
   * Unlock entity with certain id
   *
   * @param entityId entity identifier
   * @throws IllegalMonitorStateException if non-owner tries to unlock
   */
  void unlock(ID entityId);
}
