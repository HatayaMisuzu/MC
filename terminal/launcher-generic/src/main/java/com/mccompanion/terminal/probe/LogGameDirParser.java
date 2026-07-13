package com.mccompanion.terminal.probe;
import java.io.*;import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.*;import java.util.regex.*;
public final class LogGameDirParser{
 private static final Pattern ARG=Pattern.compile("--gameDir(?:=|\\s+)(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))");
 public Optional<Path> latest(List<Path> logs,String versionId){for(Path log:logs.stream().filter(Files::isRegularFile).sorted(Comparator.comparingLong(LogGameDirParser::time).reversed()).toList())try{List<String> lines=Files.readAllLines(log,StandardCharsets.UTF_8);for(int i=lines.size()-1;i>=0;i--){Matcher m=ARG.matcher(lines.get(i));while(m.find()){String s=m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3);Path p=Path.of(s).toAbsolutePath().normalize();if(p.getFileName()!=null&&p.getFileName().toString().equals(versionId))return Optional.of(p);}}}catch(IOException|RuntimeException ignored){}return Optional.empty();}
 private static long time(Path p){try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0;}}
}
