# voltdb-aggdemo
A demonstration of how VoltDB can be used for the kind of streaming aggregation tasks common in Telco

## Scenario

This demo shows how VoltDB cab be used to aggregate high volume streaming events. There are numerous situations in Telco, the IoT and other areas where we need to do this at scale 

This example is based on the author's experience writing code to handle billions of records in the Telco industry. While it's simplistic the challenges it deals with are all real ones that have been seen in the field.

In this case we are receiving a firehose of records. Each record describes internet usage for a subscriber's session as they use the web.



A session has the following fields:

sessionId - a generated ID identifies a session.
sessionStartUTC - when the session started.
callingNumber - The user who is doing the work
destination - which web resource we're looking at.

sessionId + sessionStartUTC are the 'Primary Key' of the session. 

Each session has multiple data records. Each record has:

seqno -  Am ascending gap free  integer between 0 and 255 
recordType - There will be one 'S' (Start), more than one 'I' (intermediate) and one 'E' (end).
recordStartUTC - Time the record was generated
recordUsage - Bytes if usage during this period. 

sessionId + sessionStartUTC + seqno are the 'Primary Key' of the record we receive. 

When a session's seqno gets to 255 it will be ended and will then start again. As a consequence a session can run more or less forever.

