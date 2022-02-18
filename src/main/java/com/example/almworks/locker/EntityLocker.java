package com.example.almworks.locker;

import java.util.concurrent.TimeUnit;

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
   * Attempt to get global lock
   * @return result of locking
   * @throws InterruptedException thread with lock have been interrupted
   */
  boolean globalLock(Class<?> clazz) throws InterruptedException;

  /**
   * release global lock
   */
  void globalUnlock(Class<?> clazz);

  /**
   * Lock entity with certain id
   *
   * @param entityId entity identifier
   * @param clazz    entity type
   * @return result of locking
   * @throws InterruptedException thread with lock have been interrupted
   */
  boolean lock(ID entityId, Class<?> clazz) throws InterruptedException;

  /**
   * Attempt to lock entity
   *
   * @param entityId entity identifier
   * @param clazz    entity type
   * @param timeout  timeout amount
   * @param timeUnit timeout unit
   * @return result of locking
   * @throws InterruptedException thread with lock have been interrupted
   */
  boolean lock(ID entityId, Class<?> clazz, long timeout, TimeUnit timeUnit) throws InterruptedException;

  /**
   * Unlock entity with certain id
   *
   * @param entityId entity identifier
   * @throws IllegalMonitorStateException if non-owner tries to unlock
   */
  void unlock(ID entityId, Class<?> clazz);
}
