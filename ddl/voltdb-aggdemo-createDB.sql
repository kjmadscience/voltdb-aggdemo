
LOAD CLASSES ../jars/voltdb-aggdemo.jar;

file -inlinebatch END_OF_BATCH

CREATE TABLE mediation_parameters 
(parameter_name varchar(30) not null primary key
,parameter_value bigint not null);

CREATE TABLE cdr_dupcheck
(	 sessionId bigint not null,
	 sessionStartUTC timestamp not null,
	 callingNumber varchar(20) ,
	 used_seqno_array varbinary(32),
	 insert_date timestamp not null,
	 agg_state varchar(10),
	 last_agg_date timestamp ,
	 aggregated_usage bigint default 0,
	 primary key (sessionId,sessionStartUTC)
)
USING TTL 25 HOURS ON COLUMN insert_date BATCH_SIZE 50000;

PARTITION TABLE cdr_dupcheck ON COLUMN sessionId;

CREATE INDEX cdd_ix1 ON cdr_dupcheck (insert_date);

CREATE VIEW cdr_dupcheck_agg_summary_minute AS
SELECT truncate(MINUTE, last_agg_date) last_agg_date, agg_state, count(*) how_many, sum(aggregated_usage) aggregated_usage
FROM cdr_dupcheck
GROUP BY  truncate(MINUTE, last_agg_date) , agg_state;

CREATE INDEX cdasm_ix1 ON cdr_dupcheck_agg_summary_minute (last_agg_date);

CREATE VIEW cdr_dupcheck_session_summary_minute AS
SELECT truncate(MINUTE, sessionStartUTC) sessionStartUTC, agg_state, count(*) how_many, sum(aggregated_usage) aggregated_usage
FROM cdr_dupcheck
GROUP BY  truncate(MINUTE, sessionStartUTC) , agg_state;


CREATE INDEX cdssm_ix1 ON cdr_dupcheck_session_summary_minute (sessionStartUTC);

CREATE STREAM bad_cdrs  PARTITION ON COLUMN sessionId 
(	 reason varchar(10) not null,
     sessionId bigint not null,
	 sessionStartUTC timestamp not null,
	 seqno bigint not null,
	 end_seqno bigint, 
	 callingNumber varchar(20) ,
	 destination varchar(512) not null,
	 recordType varchar(5) not null,
	 recordStartUTC timestamp not null,
	 end_recordStartUTC timestamp,
	 recordUsage bigint not null
);

CREATE TOPIC USING STREAM bad_cdrs PROFILE daily;


CREATE STREAM unaggregated_cdrs PARTITION ON COLUMN sessionId 
(	 sessionId bigint not null,
	 sessionStartUTC timestamp not null,
	 seqno bigint not null,
	 callingNumber varchar(20) ,
	 destination varchar(512) not null,
	 recordType varchar(1) not null,
	 recordStartUTC timestamp not null,
	 recordUsage bigint not null,
);




CREATE VIEW unaggregated_cdrs_by_session AS
SELECT sessionId, sessionStartUTC
     , min(recordStartUTC) min_recordStartUTC
     , max(recordStartUTC) max_recordStartUTC
     , min(seqno) min_seqno
     , max(seqno) max_seqno
     , sum(recordUsage) recordUsage
     , max(callingNumber) callingNumber
     , max(destination) destination
     , count(*) how_many 
FROM unaggregated_cdrs
GROUP BY sessionId, sessionStartUTC;

CREATE INDEX ucbs_ix1 ON unaggregated_cdrs_by_session
(min_recordStartUTC, sessionId, sessionStartUTC);



CREATE STREAM aggregated_cdrs 
PARTITION ON COLUMN sessionId
(	 reason varchar(10) not null,
     sessionId bigint not null,
	 sessionStartUTC timestamp not null,
	 min_seqno bigint not null,
	 max_seqno bigint not null,
	 callingNumber varchar(20) ,
	 destination varchar(512) not null,
	 startAggTimeUTC timestamp not null,
	 endAggTimeUTC timestamp not null,
	 recordUsage bigint not null
);

CREATE TOPIC USING STREAM aggregated_cdrs PROFILE daily;


DROP PROCEDURE HandleMediationCDR IF EXISTS;

CREATE PROCEDURE  
   PARTITION ON TABLE cdr_dupcheck COLUMN sessionid
   FROM CLASS mediationdemo.HandleMediationCDR;  
   
DROP PROCEDURE FlushStaleSessions IF EXISTS;

CREATE PROCEDURE DIRECTED
   FROM CLASS mediationdemo.FlushStaleSessions;  
   
CREATE TASK FlushStaleSessionsTask
ON SCHEDULE  EVERY 1 SECONDS
PROCEDURE FlushStaleSessions
ON ERROR LOG 
RUN ON PARTITIONS;
   

CREATE TOPIC incoming_cdrs EXECUTE PROCEDURE HandleMediationCDR  PROFILE daily;

CREATE PROCEDURE ShowAggStatus__promBL AS
BEGIN
select 'mediation_agg_state_unaggregated' statname
     ,  'mediation_agg_state_unaggregated' stathelp  
     , how_many statvalue 
from cdr_dupcheck_agg_summary_minute where last_agg_date IS null;
select 'mediation_agg_state_'||agg_state||'_qty_0min' statname
     ,  'mediation_agg_state_'||agg_state||'_qty_0min' stathelp  
     , how_many statvalue 
from cdr_dupcheck_agg_summary_minute where last_agg_date = truncate(minute,NOW);
select 'mediation_agg_state_'||agg_state||'_usage_0min' statname
     ,  'mediation_agg_state_'||agg_state||'_usage_0min' stathelp  
     , aggregated_usage statvalue 
from cdr_dupcheck_agg_summary_minute where last_agg_date = truncate(minute, NOW);
select 'mediation_agg_state_'||agg_state||'_qty_1min' statname
     ,  'mediation_agg_state_'||agg_state||'_qty_1min' stathelp  
     , how_many statvalue 
from cdr_dupcheck_agg_summary_minute where last_agg_date = truncate(minute, DATEADD(MINUTE, -1, NOW));
select 'mediation_agg_state_'||agg_state||'_usage_1min' statname
     ,  'mediation_agg_state_'||agg_state||'_usage_1min' stathelp  
     , aggregated_usage statvalue 
from cdr_dupcheck_agg_summary_minute where last_agg_date = truncate(minute, DATEADD(MINUTE, -1, NOW));
select 'mediation_agg_state_'||agg_state||'_qty_6min' statname
     ,  'mediation_agg_state_'||agg_state||'_qty_6min' stathelp  
     , how_many statvalue 
from cdr_dupcheck_agg_summary_minute where last_agg_date = truncate(minute, DATEADD(MINUTE, -6, NOW));
select 'mediation_agg_state_'||agg_state||'_usage_6min' statname
     ,  'mediation_agg_state_'||agg_state||'_usage_6min' stathelp  
     , aggregated_usage statvalue 
from cdr_dupcheck_agg_summary_minute where last_agg_date = truncate(minute, DATEADD(MINUTE, -6, NOW));
select 'mediation_parameter_'||parameter_name statname
     ,  'mediation_parameter_'||parameter_name stathelp  
     , parameter_value statvalue 
from mediation_parameters order by parameter_name;
END;

END_OF_BATCH



upsert into mediation_parameters
(parameter_name ,parameter_value)
VALUES
('AGG_THRESHOLD',50);

upsert into mediation_parameters
(parameter_name ,parameter_value)
VALUES
('AGG_QTY',100000);

upsert into mediation_parameters
(parameter_name ,parameter_value)
VALUES
('STALENESS_THRESHOLD_MS',300000);

upsert into mediation_parameters
(parameter_name ,parameter_value)
VALUES
('AGG_WINDOW_SIZE_MS',4000);

upsert into mediation_parameters
(parameter_name ,parameter_value)
VALUES
('DUPCHECK_TTLMINUTES',1440);

upsert into mediation_parameters
(parameter_name ,parameter_value)
VALUES
('STALENESS_ROWLIMIT',1000);

