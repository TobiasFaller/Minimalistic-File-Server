package org.tpc.server;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;

public class Server {

	public static void main(String[] args) throws IOException {
		try (ServerSocket serverSocket = new ServerSocket(Integer.parseInt(args[0]))) {
			Path path = Paths.get(args[1]);
	
			for (;;) {
				final Socket socket = serverSocket.accept();
				new Thread(() -> serveClient(socket, path)).start();
			}
		}
	}

	private static void serveClient(Socket socket, Path path) {
		try(InputStream in = socket.getInputStream();
				Scanner scanner = new Scanner(in);
				OutputStream out = socket.getOutputStream()) {
			String header = scanner.nextLine();
			if (!header.startsWith("GET"))
				return;

			String[] headerParts = header.split(" ");
			if (headerParts.length != 3)
				return;

			String uri = URLDecoder.decode(headerParts[1].trim(), "UTF-8");
			if (!uri.startsWith("/")) {
				uri = uri + "/";
			}

			if(uri.contains("..") || uri.contains("\\"))
				return;

			// Skip header crap
			while (!scanner.nextLine().isEmpty());

			if (uri.equals("/")) {
				uri = "/browse/";
			}

			if (uri.startsWith("/browse/")) {
				showPath(path, uri.substring(8), out);
			} else if (uri.startsWith("/download/")) {
				downloadPath(path, uri.substring(10), out);
			}

			socket.close();
		} catch (IOException | NoSuchElementException ioException) {
			ioException.printStackTrace(System.err);
		}
	}

	private static void showPath(Path rootPath, String uri, OutputStream out) throws IOException {
		System.out.println("Browse " + uri);

		Path path = rootPath.resolve(uri);
		if (!Files.exists(path) || !Files.isDirectory(path))
			return;

		if (!path.toAbsolutePath().startsWith(rootPath))
			return;

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(path);
				BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out))) {
			writer.write("HTTP/1.1 200 OK\r\n");
			writer.write("\r\n");
			writer.write("<html><head><title>Files</title></head><body>");
			writer.write(String.format("<a href=\"/download/%s\">Download folder</a><br>",
					URLEncoder.encode(rootPath.relativize(path).toString().replaceAll("\\\\", "/"), "UTF-8")));
			writer.write("<ul>");

			for (Path p : stream) {
				String relative = URLEncoder.encode(rootPath.relativize(p).toString().replaceAll("\\\\", "/"), "UTF-8");

				if (Files.isDirectory(p)) {
					writer.write(String.format("<li><a href=\"/browse/%s\">%s</a>: <a href=\"/download/%s\">Download</a></li>",
							relative, p.getFileName(), relative));
				} else {
					writer.write(String.format("<li><a href=\"/download/%s\">%s</a></li>",
							relative, p.getFileName()));
				}
			}
			writer.write("</ul></html>");
			writer.flush();
		}
	}

	private static void downloadPath(Path rootPath, String uri, OutputStream out) throws IOException {
		System.out.println("Download " + uri);

		Path path = rootPath.resolve(uri);
		if (!Files.exists(path))
			return;

		if (!path.toAbsolutePath().startsWith(rootPath))
			return;

		try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out))) {
			writer.write("HTTP/1.1 200 OK\r\n");
			writer.write("Content-Type: application/octet-stream\r\n");

			if (Files.isRegularFile(path)) { // File
				writer.write(String.format("Content-Disposition: attachment; filename=\"%s\"\r\n",
						path.getFileName().toString()));
				writer.write("\r\n");
				writer.flush();

				try (InputStream fileIn = Files.newInputStream(path)) {
					IOUtils.copy(fileIn, out);
					out.flush();
				}
			} else { // Directory
				writer.write(String.format("Content-Disposition: attachment; filename=\"%s.zip\"\r\n",
						path.getFileName().toString()));
				writer.write("\r\n");
				writer.flush();

				try (ZipOutputStream zipOut = new ZipOutputStream(out)) {
					Files.walkFileTree(path, new FileVisitor<Path>() {
						@Override
						public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
							if (dir.equals(path))
								return FileVisitResult.CONTINUE;

							zipOut.putNextEntry(new ZipEntry(path.relativize(dir).toString() + "/"));
							zipOut.closeEntry();
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
							zipOut.putNextEntry(new ZipEntry(path.relativize(file).toString()));
							try (InputStream fileIn = Files.newInputStream(file)) {
								IOUtils.copy(fileIn, zipOut);
								zipOut.flush();
							}
							zipOut.closeEntry();
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
							return FileVisitResult.CONTINUE;
						}
					});
				}
			}
		}
	}
}
