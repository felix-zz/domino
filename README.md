Domino is a system implementation of SUCC, and is open source under GPL license. 

SUCC (Stateless Update Concurrency Control) is a distributed transaction control model. SUCC 1) largely removes the conflict detection; 2) does not require rollback; 3) assists the failed commit/forward to finish. These features make SUCC support high concurrency and low delay. Meanwhile, we present P4B abnormal, a new abnormal in Snapshot Isolation (SI). SUCC prevents not only all the abnormal of SI, also P4B abnormal.
======
