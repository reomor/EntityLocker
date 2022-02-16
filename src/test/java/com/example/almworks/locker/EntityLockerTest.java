package com.example.almworks.locker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class EntityLockerTest {

  private static final String TEST_ID = "TEST_ID";
  private static final Object TEST_ENTITY = new Object();
  private static final Class<?> TEST_ENTITY_CLASS = TEST_ENTITY.getClass();

  @Test
  void lockUnlock() throws InterruptedException {

    EntityLocker<String> entityLocker = new EntityLockerImpl<>();

    boolean lockResult = entityLocker.lock(TEST_ID, TEST_ENTITY_CLASS);
    assertTrue(lockResult);

    entityLocker.unlock(TEST_ID, TEST_ENTITY_CLASS);
  }

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
  }

  @Test
  @Timeout(value = 3)
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

  @Data
  @AllArgsConstructor
  private static class Entity<ID> {
   private ID entityId;
   private int value;
  }
}
