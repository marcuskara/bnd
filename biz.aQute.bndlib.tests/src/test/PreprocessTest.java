package test;

import java.io.*;
import java.util.*;

import junit.framework.*;
import aQute.bnd.osgi.*;
import aQute.lib.io.*;
import aQute.libg.cryptography.*;

public class PreprocessTest extends TestCase {
	/**
	 * Preprocess now tries not to preprocess binary files, it uses
	 * an exclusion list by default. This list contains JAR files.
	 * 
	 */
	public static void testPreProcessDefaultExclusion() throws Exception {
		Builder b = new Builder();
		b.setProperty(Analyzer.INCLUDE_RESOURCE, "{src/test/tb1.jar}     ");
		b.build();
		assertTrue(b.check());

		Jar jar = b.getJar();
		Resource resource = jar.getResource("tb1.jar");
		
		assertNotNull( resource);
		
		File f = IO.getFile("src/test/tb1.jar");
		SHA1 d1 = SHA1.digest(f);
		SHA1 d2 = SHA1.digest(resource.openInputStream());
		assertEquals(d1, d2);
		
		b.close();
	}

	/**
	 * Check if we can override 
	 * @throws Exception
	 */
	public static void testPreProcessExcludeExtensionGlobal() throws Exception {
		Builder b = new Builder();
		b.setProperty(Analyzer.PREPROCESSMATCHERS, "!*.TXT:i,*");
		b.setProperty(Analyzer.INCLUDE_RESOURCE, "{src/test/builder-preprocess.txt},{src/test/builder-preprocess.txt2}");
		b.setProperty("var", "Yes!");;
		b.build();
		assertTrue(b.check());
		System.out.println("testPreProcessExcludeExtensionsGlobal");

		Jar jar = b.getJar();
		Resource resource = jar.getResource("builder-preprocess.txt");
		String s = IO.collect(resource.openInputStream());
		System.out.println(s);
		assertTrue( s.contains("${var}"));
		assertFalse( s.contains("!Yes"));
		
		resource = jar.getResource("builder-preprocess.txt2");
		s = IO.collect(resource.openInputStream());
		System.out.println(s);
		assertTrue( s.contains("Yes!"));
		assertFalse( s.contains("${var}"));
		b.close();
	}

	/**
	 * Exclude a file with a specific extension from being processed
	 */
	public static void testPreProcessExcludeExtensionLocal() throws Exception {
		Builder b = new Builder();
		b.setProperty(Analyzer.INCLUDE_RESOURCE, "{src/test/builder-preprocess.txt};-preprocessmatchers='!*.TXT:i,*'");
		b.setProperty("var", "Yes!");;
		b.build();
		assertTrue(b.check());

		Jar jar = b.getJar();
		Resource resource = jar.getResource("builder-preprocess.txt");
		assertNotNull( resource);
		String s = IO.collect(resource.openInputStream());
		assertNotNull(s);
		
		assertTrue( s.contains("${var}"));
		b.close();
	}
	
	/**
	 * Spaces at the end of a clause cause the preprocess to fail.
	 * 
	 * @throws Exception
	 */
	public static void testPreProcess() throws Exception {
		Properties base = new Properties();
		base.put(Analyzer.INCLUDE_RESOURCE, "{src/test/top.mf}     ");
		Builder analyzer = new Builder();
		analyzer.setProperties(base);
		analyzer.build();
		assertTrue(analyzer.check());

		Jar jar = analyzer.getJar();
		assertTrue(jar.getResource("top.mf") != null);
		analyzer.close();
	}



}
