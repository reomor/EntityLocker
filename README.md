### Main task

[![Java CI with Maven](https://github.com/reomor/EntityLocker/actions/workflows/maven.yml/badge.svg)](https://github.com/reomor/EntityLocker/actions/workflows/maven.yml)
[![codecov](https://codecov.io/gh/reomor/EntityLocker/branch/main/graph/badge.svg?token=U1T2UIM93I)](https://codecov.io/gh/reomor/EntityLocker)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=reomor_EntityLocker&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=reomor_EntityLocker)

The task is to create a reusable utility class that provides synchronization mechanism similar to row-level DB locking.
<p>
The class is supposed to be used by the components that are responsible for managing storage and caching of different
type of entities in the application.
EntityLocker itself does not deal with the entities, only with the IDs (primary keys) of the entities.
<p>

Disclaimer:
The main idea is about the fact that Entities have separate class and same EntityLocker can work with different entities with same ID type.
So basic type parameters are: Entity class and Entity ID. 
There is no explicit protection from deadlock and some tests have helped me find one (order in which global and inner lock are taken). 
Writing tests and code analysis are still main drivers.

<p>
Requirements:

- [x] `task-01` EntityLocker should support different types of entity IDs
- [x] `task-02` EntityLocker’s interface should allow the caller to specify which entity does it want to work with (using entity ID),
  and designate the boundaries of the code that should have exclusive access to the entity (called “protected code”)
- [x] `task-03` For any given entity, EntityLocker should guarantee that at most one thread executes protected code on that entity.
  If there’s a concurrent request to lock the same entity, the other thread should wait until the entity becomes available.
- [x] `task-04` EntityLocker should allow concurrent execution of protected code on different entities.

<p>
Bonus requirements (optional):

- [x] `task-05` Allow reentrant locking
- [x] `task-06` Allow the caller to specify timeout for locking an entity
- [ ] `task-07` Implement protection from deadlocks (but not taking into account possible locks outside EntityLocker)
- [x] `task-08` Implement global lock. Protected code that executes under a global lock must not execute concurrently with any other protected code
- [x] `task-09` Implement lock escalation. If a single thread has locked too many entities, escalate its lock to be a global lock. 
