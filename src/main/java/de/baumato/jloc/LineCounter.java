package de.baumato.jloc;

import static de.baumato.jloc.util.MorePaths.endsWithIgnoreCase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.stream.Stream;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;

import de.baumato.jloc.configuration.Configuration;
import de.baumato.jloc.file.CharsetTryingFileReader;
import de.baumato.jloc.messages.Messages;
import de.baumato.jloc.printer.ConsolePrinter;

public class LineCounter {

  private static final Formatter formatter = new Formatter(); // new formatter with default options
  private final Configuration conf;
  private final ConsolePrinter printer;

  public LineCounter(Configuration conf, ConsolePrinter printer) {
    this.conf = conf;
    this.printer = printer;
  }

  public long count() {
    printer.step(Messages.FILE.get() + ";" + Messages.COUNT.get());
    long sum = countLinesInDir();
    printer.step(Messages.SUM.get() + ";" + sum);
    return sum;
  }

  private long countLinesInDir() {
    try (Stream<Path> paths = Files.walk(conf.getDirectory())) {
      return paths
          .filter(p -> endsWithIgnoreCase(p, ".java"))
          .filter(p -> !pathContainsOneOfDirs(p, conf.getExcludeDirs()))
          .mapToLong(this::countLinesInFile)
          .sum();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean pathContainsOneOfDirs(Path p, Collection<String> excludeDirs) {
    for (int i = 0; i < p.getNameCount(); i++) {
      if (excludeDirs.contains(p.getName(i).toString())) {
        return true;
      }
    }
    return false;
  }

  private long countLinesInFile(Path p) {
    long count = countLines(p);
    printer.step(p.toString() + ";" + count);
    return count;
  }

  long countLines(Path p) {
    try (Stream<String> lines = readFileNormalized(p).lines()) {
      return lines
          .filter(line -> countEmptyLines() || (!countEmptyLines() && !line.isBlank()))
          .count();
    }
  }

  private String readFileNormalized(Path p) {
    try {
      String fileContent = new CharsetTryingFileReader(p).read();
      // remove comments when not counting them
      if (!countComments()) {
        CompilationUnit cu = JavaParser.parse(fileContent);
        removeAllComments(cu);
        fileContent = cu.toString();
        conf.getAppLogger().trace("{} has been cleaned from comments", p);
      }
      // normalize java file by formatting it
      fileContent = formatWithGoogleFormatter(fileContent);
      conf.getAppLogger().trace("{} has been normalized", p);
      return fileContent;
    } catch (IOException | ParseProblemException | FormatterException e) {
      conf.getAppLogger()
          .error(
              "Error during reading '{}'. Error was: {} => Fallback to simple counting.",
              p,
              e.getClass().getSimpleName());
      return readFile(p);
    }
  }

  private static String formatWithGoogleFormatter(String sourceCode) throws FormatterException {
    return formatter.formatSource(sourceCode);
  }

  private static String readFile(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new UncheckedIOException("Error during reading: " + p, e);
    }
  }

  private boolean countComments() {
    return conf.getCalculationMode().countComments;
  }

  private static void removeAllComments(CompilationUnit cu) {
    cu.getAllContainedComments().forEach(Comment::remove);
  }

  private boolean countEmptyLines() {
    return conf.getCalculationMode().countEmptyLines;
  }
}
