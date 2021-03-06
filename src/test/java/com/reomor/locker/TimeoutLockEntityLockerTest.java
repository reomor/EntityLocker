package com.reomor.locker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import manifold.ext.rt.api.Jailbreak;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Slf4j
public class TimeoutLockEntityLockerTest extends AbstractEntityLockerTest {

  @Test
  @Timeout(value = 5)
  void timeoutLock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // first thread always takes lock first
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        completeLatch.countDown();

        Thread.sleep(1500);

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    Thread thread2 = new Thread(() -> {
      try {
        thread2StartLatch.await();

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertFalse(lockResult);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();
  }

  @Test
  @Timeout(value = 3)
  void whenLockTimeout_EscalateToGlobalSuccessfully() throws InterruptedException {

    @Jailbreak
    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>(2);

    // for type-safe reflection
    // this approach differs from 'protected methods' like getNumberOfLockedObject
    // but without maliford plugin rather difficult to develop
    EntityLockerImpl<String> jailbreak = entityLocker.jailbreak();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);
        lockResult = entityLocker.lock(TEST_ID2, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        phase1.countDown();

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        boolean lockResult = entityLocker.lock(TEST_ID3, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();

    ReentrantLock classGlobalLock = jailbreak.getCurrentClassGlobalLock(TEST_ENTITY_CLASS);

    assertFalse(classGlobalLock.isLocked());
  }

  @Test
  @Timeout(value = 3)
  void whenLockTimeout_EscalateToGlobalFailed() throws InterruptedException {

    @Jailbreak
    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>(2);
    EntityLockerImpl<String> jailbreak = entityLocker.jailbreak();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch phase2 = new CountDownLatch(1);
    CountDownLatch phase3 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    Thread thread1 = new Thread(() -> {
      try {

        boolean lockResult = entityLocker.lock(TEST_ID3, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        phase1.countDown();
        phase2.await();

        entityLocker.unlock(TEST_ID3, TEST_ENTITY_CLASS);

        phase3.countDown();

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);
        lockResult = entityLocker.lock(TEST_ID2, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        phase2.countDown();
        phase3.await();

        long threadId = Thread.currentThread().getId();
        Set<String> lockedEntityIds = entityLocker.jailbreak().threadLockedEntities.get(threadId).get(TEST_ENTITY_CLASS);

        Set<String> expectedEntityIds = Set.of(TEST_ID, TEST_ID2);
        assertTrue(lockedEntityIds.containsAll(expectedEntityIds));

        assertEquals(expectedEntityIds.size(), entityLocker.jailbreak().getNumberOfLockedByThreadEntities(TEST_ENTITY_CLASS));

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
        entityLocker.unlock(TEST_ID2, TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();

    ReentrantLock classGlobalLock = jailbreak.getCurrentClassGlobalLock(TEST_ENTITY_CLASS);

    assertFalse(classGlobalLock.isLocked());
  }

  @Test
  @Timeout(value = 3)
  void whenLockTimeoutUnlock_AllConditionsAreSavedProperly() throws InterruptedException {

    @Jailbreak
    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>();
    EntityLockerImpl<String> jailbreak = entityLocker.jailbreak();

    CountDownLatch completeLatch = new CountDownLatch(1);

    Thread thread = new Thread(() -> {
      try {

        // lock
        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        assertNotNull(entityLocker.jailbreak().clazzGlobalLocks.get(TEST_ENTITY_CLASS));
        assertNotNull(entityLocker.jailbreak().clazzGlobalLocksConditions.get(TEST_ENTITY_CLASS));
        assertNotNull(entityLocker.jailbreak().entitiesLockMaps.get(TEST_ENTITY_CLASS).get(TEST_ID));

        long threadId = Thread.currentThread().getId();
        Set<String> entityIds = entityLocker.jailbreak().threadLockedEntities.get(threadId).get(TEST_ENTITY_CLASS);
        assertTrue(entityIds.contains(TEST_ID));

        assertEquals(1, entityLocker.jailbreak().clazzNumberOfLockedObjects.get(TEST_ENTITY_CLASS).get());

        // unlock
        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
        entityIds = entityLocker.jailbreak().threadLockedEntities.get(threadId).get(TEST_ENTITY_CLASS);
        assertTrue(entityIds.isEmpty());
        assertEquals(0, entityLocker.jailbreak().clazzNumberOfLockedObjects.get(TEST_ENTITY_CLASS).get());

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread.start();

    completeLatch.await();

    ReentrantLock classGlobalLock = jailbreak.getCurrentClassGlobalLock(TEST_ENTITY_CLASS);

    assertFalse(classGlobalLock.isLocked());
  }

  @Test
  @Timeout(value = 3)
  void removeAfterUnlock() throws InterruptedException {

    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>();

    final long threadId = Thread.currentThread().getId();

    boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
    assertTrue(lockResult);

    assertNotNull(entityLocker.jailbreak().entitiesLockMaps.get(TEST_ENTITY_CLASS).get(TEST_ID));
    assertNotNull(entityLocker.jailbreak().threadLockedEntities.get(threadId).get(TEST_ENTITY_CLASS));
    assertEquals(1, entityLocker.jailbreak().clazzNumberOfLockedObjects.get(TEST_ENTITY_CLASS).get());

    entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);

    assertNull(entityLocker.jailbreak().entitiesLockMaps.get(TEST_ENTITY_CLASS).get(TEST_ID));
    assertTrue(entityLocker.jailbreak().threadLockedEntities.get(threadId).get(TEST_ENTITY_CLASS).isEmpty());
    assertEquals(0, entityLocker.jailbreak().clazzNumberOfLockedObjects.get(TEST_ENTITY_CLASS).get());
  }

  @Data
  @AllArgsConstructor
  private static class Entity<ID> {
    private ID entityId;
    private int value;
  }
}
