#!/bin/sh


sqlcmd --servers=vdb1 < ../ddl/Ivoltdb-aggdemo-createDB.sql
java -jar $HOME/bin/addtodeploymentdotxml.jar vdb1,vdb2,vdb3 deployment topics.xml
