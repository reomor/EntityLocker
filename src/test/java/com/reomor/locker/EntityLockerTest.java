package com.reomor.locker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import manifold.ext.rt.api.Jailbreak;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class EntityLockerTest {

  private static final String TEST_ID = "TEST_ID";
  private static final String TEST_ID2 = "TEST_ID2";
  private static final String TEST_ID3 = "TEST_ID3";
  private static final Class<?> TEST_ENTITY_CLASS = Object.class;

  @Test
  void lockUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
    assertTrue(lockResult);

    entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
  }

  @Test
  void lockUnlock_throwsException() throws InterruptedException {

    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicInteger errors = new AtomicInteger(0);

    ReentrantLock lock = entityLocker.jailbreak().getOrCreateLock(TEST_ID, TEST_ENTITY_CLASS);

    Thread thread1 = new Thread(() -> {
      try {
        phase1.await();
        entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
      } catch (InterruptedException e) {
        errors.incrementAndGet();
      } finally {
        completeLatch.countDown();
      }
    });

    thread1.start();
    phase1.countDown();

    thread1.interrupt();

    completeLatch.await();

    assertEquals(1, errors.get());
  }

//  @Test
  @Timeout(value = 3)
  @RepeatedTest(50)
  void lockUnlockMultiThreaded() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();
    AtomicInteger errors = new AtomicInteger(0);

    int numberOfThreads = 10;
    ExecutorService service = Executors.newFixedThreadPool(numberOfThreads);

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch waitLatch = new CountDownLatch(numberOfThreads);

    for (int i = 0; i < numberOfThreads; i++) {
      service.submit(() -> {

        try {
          startLatch.await();
        } catch (InterruptedException ignore) {
          /* NOP */
        }

        try {
          boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
          assertTrue(lockResult);
          entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
        } catch (InterruptedException e) {
          errors.incrementAndGet();
        }

        waitLatch.countDown();
      });
    }

    assertEquals(0, errors.get());

    // run the process
    startLatch.countDown();

    // await all thread complete
    waitLatch.await();
  }

  @Timeout(value = 3)
  @RepeatedTest(50)
  void lockOneAfterAnother() throws InterruptedException {

    @Jailbreak
    Entity<String> entity = new Entity<>(TEST_ID, 0);
    Class<?> entityClass = entity.getClass();
    int expectedValue = 2;

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();
    AtomicInteger errors = new AtomicInteger(0);

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // first thread always takes lock first
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.lock(entity.getEntityId(), entityClass);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        entity.setValue(expectedValue - 1);
        entityLocker.unlock(entity.getEntityId(), entityClass);

        completeLatch.countDown();
      } catch (InterruptedException e) {
        errors.incrementAndGet();
      }
    });

    Thread thread2 = new Thread(() -> {
      try {
        thread2StartLatch.await();

        boolean lockResult = entityLocker.lock(entity.getEntityId(), entityClass);
        assertTrue(lockResult);

        entity.setValue(expectedValue);
        entityLocker.unlock(entity.getEntityId(), entityClass);

        completeLatch.countDown();
      } catch (InterruptedException e) {
        errors.incrementAndGet();
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();

    assertEquals(0, errors.get());
    assertEquals(expectedValue, entity.getValue());
    // dirty hack
    assertEquals(0, ((EntityLockerImpl) entityLocker).getNumberOfLockedObject(TEST_ENTITY_CLASS));
  }

  @Test
  @Timeout(value = 3)
  void lockOneAnotherTriesToUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();
    AtomicInteger errors = new AtomicInteger(0);

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // first thread always takes lock first
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    Thread thread2 = new Thread(() -> {
      try {
        thread2StartLatch.await();

        try {
          entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
        } catch (IllegalMonitorStateException e) {
          errors.incrementAndGet();
        }

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();

    assertEquals(1, errors.get());
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 5})
  void reentrancyTest(int attempts) throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    for (int i = 0; i < attempts; i++) {
      assertTrue(entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS));
    }

    entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
  }

  @Test
  @Timeout(value = 3)
  void timeoutLockReentrancy() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
    assertTrue(lockResult);

    boolean timeoutLockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
    assertTrue(timeoutLockResult);

    entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
  }

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
  void whenGlobalLock_successLockAttempt_afterGlobalUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        thread2StartLatch.await();

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        completeLatch.countDown();

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
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
  void whenGlobalLock_failTimeoutLockAttempt() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
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
  void whenLock_successGlobalLockAttempt_afterUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch thread2StartLatch = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        thread2StartLatch.countDown();

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        thread2StartLatch.await();

        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

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
  void whenGlobalLock_LockIsPossibleAfterGlobalUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch phase2 = new CountDownLatch(1);
    CountDownLatch phase3 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    // takes successfully global lock
    Thread thread1 = new Thread(() -> {
      try {
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        phase1.countDown();
        phase2.await();

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);
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
        assertFalse(lockResult);

        phase2.countDown();
        phase3.await();

        lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

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
  void whenLock_EscalateToGlobalSuccessfully() throws InterruptedException {

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
        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
        assertTrue(lockResult);
        lockResult = entityLocker.lock(TEST_ID2, TEST_ENTITY_CLASS);
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

        boolean lockResult = entityLocker.lock(TEST_ID3, TEST_ENTITY_CLASS);
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
  void whenLock_EscalateToGlobalFailed() throws InterruptedException {

    @Jailbreak
    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>(2);
    EntityLockerImpl<String> jailbreak = entityLocker.jailbreak();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    Thread thread1 = new Thread(() -> {
      try {

        boolean lockResult = entityLocker.lock(TEST_ID3, TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        phase1.countDown();

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
        assertTrue(lockResult);
        lockResult = entityLocker.lock(TEST_ID2, TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        long threadId = Thread.currentThread().getId();
        Set<String> lockedEntityIds = entityLocker.jailbreak().threadLockedEntities.get(threadId).get(TEST_ENTITY_CLASS);

        Set<String> expectedEntityIds = Set.of(TEST_ID, TEST_ID2);
        assertTrue(lockedEntityIds.containsAll(expectedEntityIds));

        assertEquals(expectedEntityIds.size(), entityLocker.jailbreak().getNumberOfLockedByThreadEntities(TEST_ENTITY_CLASS));

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
//  @Timeout(value = 3)
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
  void whenLockUnlock_AllConditionsAreSavedProperly() throws InterruptedException {

    @Jailbreak
    EntityLockerImpl<String> entityLocker = new EntityLockerImpl<>();
    EntityLockerImpl<String> jailbreak = entityLocker.jailbreak();

    CountDownLatch completeLatch = new CountDownLatch(1);

    Thread thread = new Thread(() -> {
      try {

        // lock
        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
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
  void whenGlobalLock_waitingEntityUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    final int globalLockWaiting = 1000;

    Thread thread1 = new Thread(() -> {
      try {

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
        assertTrue(lockResult);

        phase1.countDown();

        // after 1 seconds entity unlocks
        Thread.sleep(globalLockWaiting);

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        long startTime = System.currentTimeMillis();
        // that should cause infinite waiting
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);

        long endTime = System.currentTimeMillis();

        // waiting is approximately [990,1010] milliseconds
        assertTrue(Math.abs(endTime - startTime - globalLockWaiting) <= 10);

        // successfully locked
        assertTrue(lockResult);

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

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
  void whenGlobalLockTimeout_waitingEntityUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    CountDownLatch phase1 = new CountDownLatch(1);
    CountDownLatch completeLatch = new CountDownLatch(2);

    final int globalLockWaiting = 1000;

    Thread thread1 = new Thread(() -> {
      try {

        boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS, 1, TimeUnit.SECONDS);
        assertTrue(lockResult);

        phase1.countDown();

        // after 1 seconds entity unlocks
        Thread.sleep(globalLockWaiting);

        entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    //
    Thread thread2 = new Thread(() -> {
      try {
        phase1.await();

        long startTime = System.currentTimeMillis();
        // that should cause infinite waiting
        boolean lockResult = entityLocker.globalLock(TEST_ENTITY_CLASS);

        long endTime = System.currentTimeMillis();

        // waiting is approximately [990,1010] milliseconds
        assertTrue(Math.abs(endTime - startTime - globalLockWaiting) <= 10);

        // successfully locked
        assertTrue(lockResult);

        entityLocker.globalUnlock(TEST_ENTITY_CLASS);

        completeLatch.countDown();
      } catch (InterruptedException ignore) {
        /* NOP */
      }
    });

    thread1.start();
    thread2.start();

    completeLatch.await();
  }

  @Data
  @AllArgsConstructor
  private static class Entity<ID> {
   private ID entityId;
   private int value;
  }
}
