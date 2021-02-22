#!/bin/sh

cd /home/ubuntu
. ./.profile

cd voltdb-aggdemo/scripts

sqlcmd --servers=vdb1 < ../ddl/voltdb-aggdemo-createDB.sql
java -jar $HOME/bin/addtodeploymentdotxml.jar vdb1,vdb2,vdb3 deployment topics.xml
