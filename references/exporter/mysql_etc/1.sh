kill -9 5781 5786 5788 5791 5796 5801
rm -rf /tmp/sql_ex*
./mysql_exporter_1.start
./mysql_exporter_2.start
./mysql_exporter_3.start
./mysql_exporter_4.start
./mysql_exporter_5.start
./mysql_exporter_6.start
echo 'ls -ld /tmp/sql_ex*'
