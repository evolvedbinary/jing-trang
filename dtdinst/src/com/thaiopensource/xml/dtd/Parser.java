package com.thaiopensource.xml.dtd;

import java.io.Reader;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

public class Parser extends Token {
  private Parser parent;
  private Reader in;
  private char[] buf;
  private int bufStart = 0;
  private int bufEnd;
  private int currentTokenStart = 0;
  // The offset in buffer corresponding to pos.
  private int posOff = 0;
  private long bufEndStreamOffset = 0;
  private Position pos = new Position();

  private static final int READSIZE = 1024*8;
  // Some temporary buffers
  private Buffer valueBuf;
  private Hashtable paramEntityTable;
  private Vector atoms = new Vector();

  static class Atom {
    private int tokenType;
    private String token;
    private EntityImpl entity;

    Atom(EntityImpl entity) {
      this.entity = entity;
      this.tokenType = -1;
      this.token = null;
    }

    Atom(int tokenType, String token) {
      this.tokenType = tokenType;
      this.token = token;
    }

    final int getTokenType() {
      return tokenType;
    }

    final String getToken() {
      return token;
    }

    final EntityImpl getEntity() {
      return entity;
    }

    public int hashCode() {
      return token.hashCode();
    }

    public boolean equals(Object obj) {
      if (obj == null || !(obj instanceof Atom))
	return false;
      Atom other = (Atom)obj;
      if (this.entity != null)
	return this.entity == other.entity;
      else
	return this.tokenType == other.tokenType && this.token.equals(other.token);
    }
  }

  static class EntityImpl {
    final String name;
    EntityImpl(String name) { this.name = name; }
    char[] text;
    boolean open;
    String notationName;
    Vector atoms;
  }

  static class DeclState {
    EntityImpl entity;
  }

  public Parser(Reader in) {
    this.in = in;
    this.parent = null;
    this.buf = new char[READSIZE * 2];
    this.valueBuf = new Buffer();
    this.bufEnd = 0;
    this.paramEntityTable = new Hashtable();
  }

  private Parser(char[] buf, String entityName, boolean isParameterEntity, Parser parent) {
    // this.internalEntityName = entityName;
    // this.isParameterEntity = isParameterEntity;
    this.buf = buf;
    this.parent = parent;
    //baseURL = parent.baseURL;
    //entityManager = parent.entityManager;
    this.bufEnd = buf.length;
    this.bufEndStreamOffset = buf.length;
    this.valueBuf = parent.valueBuf;
    this.paramEntityTable = parent.paramEntityTable;
  }

  public void parse() throws IOException {
    parseDecls(false);
    dumpEntity("#doc", atoms);
  }

  private void parseDecls(boolean isInternal) throws IOException {
    PrologParser pp = new PrologParser(isInternal
				       ? PrologParser.INTERNAL_ENTITY
				       : PrologParser.EXTERNAL_ENTITY);
    DeclState declState = new DeclState();
    try {
      for (;;) {
	int tok;
	try {
	  tok = tokenizeProlog();
	}
	catch (EndOfPrologException e) {
	  fatal("SYNTAX_ERROR");
          break;
	}
	catch (EmptyTokenException e) {
	  pp.end();
	  break;
	}
	prologAction(tok, pp, declState);
      }
    }
    catch (PrologSyntaxException e) {
      fatal("SYNTAX_ERROR");
    }
    finally {
      if (!isInternal && in != null) {
	in.close();
	in = null;
      }
    }
  }

  private void prologAction(int tok, PrologParser pp, DeclState declState)
    throws IOException, PrologSyntaxException {
    if (tok != Tokenizer.TOK_PARAM_ENTITY_REF)
      addAtom(new Atom(tok, new String(buf,
				       currentTokenStart,
				       bufStart - currentTokenStart)));
    int action = pp.action(tok, buf, currentTokenStart, bufStart);
    switch (action) {
    case PrologParser.ACTION_GENERAL_ENTITY_NAME:
      declState.entity = null;
      break;
    case PrologParser.ACTION_PARAM_ENTITY_NAME:
      {
	String name = new String(buf,
				 currentTokenStart,
				 bufStart - currentTokenStart);
	declState.entity = createParamEntity(name);
	break;
      }
    case PrologParser.ACTION_ENTITY_VALUE_WITH_PEREFS:
      if (declState.entity != null)
	declState.entity.text = makeReplacementText();
      break;
    case PrologParser.ACTION_INNER_PARAM_ENTITY_REF:
    case PrologParser.ACTION_OUTER_PARAM_ENTITY_REF:
      {
	int nameStart = currentTokenStart + 1;
	String name = new String(buf, nameStart, getNameEnd() - nameStart);
	EntityImpl entity = lookupParamEntity(name);
	if (entity == null) {
	  fatal("UNDEF_PEREF", name);
	  break;
	}
	Parser parser = makeParserForEntity(entity, name, true);
	if (parser == null) {
	  //XXX
	  break;
	}
	entity.open = true;
	if (action == PrologParser.ACTION_OUTER_PARAM_ENTITY_REF)
	  parser.parseDecls(entity.text != null);
	else
	  parser.parseInnerParamEntity(pp, declState);
	entity.atoms = parser.atoms;
	addAtom(new Atom(entity));
	entity.open = false;
	break;
      }
    }
  }

  void parseInnerParamEntity(PrologParser pp, DeclState declState) throws IOException {
    int groupLevel = pp.getGroupLevel();
    try {
      for (;;) {
	int tok = tokenizeProlog();
	prologAction(tok, pp, declState);
	if (tok == Tokenizer.TOK_DECL_CLOSE)
	  fatal("PE_DECL_NESTING");
      }
    }
    catch (EndOfPrologException e) {
      fatal("SYNTAX_ERROR");
    }
    catch (PrologSyntaxException e) {
      fatal("SYNTAX_ERROR");
    }
    catch (EmptyTokenException e) { }
    if (pp.getGroupLevel() != groupLevel)
      fatal("PE_GROUP_NESTING");
  }


  private Parser makeParserForEntity(EntityImpl entity, String name, boolean isParameter) throws IOException {
    if (entity.open)
      fatal("RECURSION");
    if (entity.notationName != null)
      fatal("UNPARSED_REF");
    if (entity.text != null)
      return new Parser(entity.text, name, isParameter, this);
    // XXX
    return null;

    //OpenEntity openEntity
    //  = entityManager.open(entity.systemId, entity.baseURL, entity.publicId);
    //if (openEntity == null)
    //  return null;
    //return new EntityParser(openEntity, entityManager, app, locale, this);
  }


  /*
   * Make the replacement text for an entity out of the literal in the
   * current token.
   */
  private char[] makeReplacementText() throws IOException {
    valueBuf.clear();
    Token t = new Token();
    int start = currentTokenStart + 1;
    final int end = bufStart - 1;
    try {
      for (;;) {
	int tok;
	int nextStart;
	try {
	  tok = Tokenizer.tokenizeEntityValue(buf, start, end, t);
	  nextStart = t.getTokenEnd();
	}
	catch (ExtensibleTokenException e) {
	  tok = e.getTokenType();
	  nextStart = end;
	}
	handleEntityValueToken(valueBuf, tok, start, nextStart, t);
	start = nextStart;
      }
    }
    catch (PartialTokenException e) {
      currentTokenStart = end;
      fatal("NOT_WELL_FORMED");
    }
    catch (InvalidTokenException e) {
      currentTokenStart = e.getOffset();
      reportInvalidToken(e);
    }
    catch (EmptyTokenException e) { }

    return valueBuf.getChars();
  }

  private void parseEntityValue(Buffer value) throws IOException {
    final Token t = new Token();
    for (;;) {
      int tok;
      for (;;) {
	try {
	  tok = Tokenizer.tokenizeEntityValue(buf, bufStart, bufEnd, t);
	  currentTokenStart = bufStart;
	  bufStart = t.getTokenEnd();
	  break;
	}
	catch (EmptyTokenException e) {
	  if (!fill())
	    return;
	}
	catch (PartialTokenException e) {
	  if (!fill()) {
	    currentTokenStart = bufStart;
	    bufStart = bufEnd;
	    fatal("UNCLOSED_TOKEN");
	  }
	}
	catch (ExtensibleTokenException e) {
	  if (!fill()) {
	    currentTokenStart = bufStart;
	    bufStart = bufEnd;
	    tok = e.getTokenType();
	    break;
	  }
	}
	catch (InvalidTokenException e) {
	  currentTokenStart = e.getOffset();
	  reportInvalidToken(e);
	}
      }
      handleEntityValueToken(value, tok, currentTokenStart, bufStart, t);
    }
  }

  private void handleEntityValueToken(Buffer value, int tok, int start, int end, Token t) throws IOException {
    switch (tok) {
    case Tokenizer.TOK_DATA_CHARS:
    case Tokenizer.TOK_ENTITY_REF:
    case Tokenizer.TOK_MAGIC_ENTITY_REF:
      value.append(buf, start, end);
      break;
    case Tokenizer.TOK_CHAR_REF:
      value.append(t.getRefChar());
      break;
    case Tokenizer.TOK_CHAR_PAIR_REF:
      value.appendRefCharPair(t);
      break;
    case Tokenizer.TOK_DATA_NEWLINE:
      value.append('\n');
      break;
    case Tokenizer.TOK_PARAM_ENTITY_REF:
      String name = new String(buf, start + 1, end - start - 2);
      EntityImpl entity = lookupParamEntity(name);
      if (entity == null) {
	fatal("UNDEF_PEREF", name);
	break;
      }
      Parser parser = makeParserForEntity(entity, name, true);
      if (parser != null) {
	entity.open = true;
	parser.parseEntityValue(value);
	entity.open = false;
      }
      break;
    default:
      throw new Error("replacement text botch");
    }
  }

  private final int tokenizeProlog()
       throws IOException, EmptyTokenException, EndOfPrologException {
    for (;;) {
      try {
	int tok = Tokenizer.tokenizeProlog(buf, bufStart, bufEnd, this);
	currentTokenStart = bufStart;
	bufStart = getTokenEnd();
	return tok;
      }
      catch (EmptyTokenException e) {
	if (!fill())
	  throw e;
      }
      catch (PartialTokenException e) {
	if (!fill()) {
	  currentTokenStart = bufStart;
	  bufStart = bufEnd;
	  fatal("UNCLOSED_TOKEN");
	}
      }
      catch (ExtensibleTokenException e) {
	if (!fill()) {
	  currentTokenStart = bufStart;
	  bufStart = bufEnd;
	  return e.getTokenType();
	}
      }
      catch (InvalidTokenException e) {
	bufStart = currentTokenStart = e.getOffset();
	reportInvalidToken(e);
      }
    }
  }

  /* The size of the buffer is always a multiple of READSIZE.
     We do reads so that a complete read would end at the
     end of the buffer.  Unless there has been an incomplete
     read, we always read in multiples of READSIZE. */
  private boolean fill() throws IOException {
    if (in == null)
      return false;
    if (bufEnd == buf.length) {
      Tokenizer.movePosition(buf, posOff, bufStart, pos);
      /* The last read was complete. */
      int keep = bufEnd - bufStart;
      if (keep == 0)
	bufEnd = 0;
      else if (keep + READSIZE <= buf.length) {
	/*
	 * There is space in the buffer for at least READSIZE bytes.
	 * Choose bufEnd so that it is the least non-negative integer
	 * greater than or equal to <code>keep</code>, such
	 * <code>bufLength - keep</code> is a multiple of READSIZE.
	 */
	bufEnd = buf.length - (((buf.length - keep)/READSIZE) * READSIZE);
	for (int i = 0; i < keep; i++)
	  buf[bufEnd - keep + i] = buf[bufStart + i];
      }
      else {
	char newBuf[] = new char[buf.length << 1];
	bufEnd = buf.length;
	System.arraycopy(buf, bufStart, newBuf, bufEnd - keep, keep);
	buf = newBuf;
      }
      bufStart = bufEnd - keep;
      posOff = bufStart;
    }
    int nChars = in.read(buf, bufEnd, buf.length - bufEnd);
    if (nChars < 0) {
      in.close();
      in = null;
      return false;
    }
    bufEnd += nChars;
    bufEndStreamOffset += nChars;
    return true;
  }

  private EntityImpl lookupParamEntity(String name) {
    return (EntityImpl)paramEntityTable.get(name);
  }

  private EntityImpl createParamEntity(String name) {
    EntityImpl e = (EntityImpl)paramEntityTable.get(name);
    if (e != null)
      return null;
    e = new EntityImpl(name);
    paramEntityTable.put(name, e);
    return e;
  }

  private void fatal(String s, String arg) throws IOException {
    throw new IOException(s + ": " + arg);
  }
  
  private void fatal(String s) throws IOException {
    // XXX
    throw new IOException(s);
  }

  private void reportInvalidToken(InvalidTokenException e) throws IOException {
    // XXX
    fatal("INVALID_TOKEN");
  }

  private void addAtom(Atom a) {
    atoms.addElement(a);
  }

  private void dumpEntity(String name, Vector atoms) {
    System.out.println("<e name=\"" + name + "\">");
    dumpAtoms(atoms);
    System.out.println("</e>");
  }

  private void dumpAtoms(Vector v) {
    int n = v.size();
    for (int i = 0; i < n; i++) {
      Atom a = (Atom)v.elementAt(i);
      if (a.getTokenType() < 0) {
	EntityImpl e = a.getEntity();
	dumpEntity(e.name, e.atoms);
      }
      else {
	System.out.print("<t>");
	dumpString(a.getToken());
	System.out.println("</t>");
      }
    }
  }
  
  private void dumpString(String s) {
    int n = s.length();
    for (int i = 0; i < n; i++)
      switch (s.charAt(i)) {
      case '<':
	System.out.print("&lt;");
	break;
      case '>':
	System.out.print("&gt;");
	break;
      case '&':
	System.out.print("&amp;");
	break;
      default:
	System.out.print(s.charAt(i));
	break;
      }
  }

}
