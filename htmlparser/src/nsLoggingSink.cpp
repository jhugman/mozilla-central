/* -*- Mode: C++; tab-width: 2; indent-tabs-mode: nil; c-basic-offset: 2 -*-
 *
 * The contents of this file are subject to the Netscape Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/NPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is Mozilla Communicator client code.
 *
 * The Initial Developer of the Original Code is Netscape Communications
 * Corporation.  Portions created by Netscape are
 * Copyright (C) 1998 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 *  Notes: 
 *    The logging sink is now both a sink and a proxy. 
 *    If you want to dump the calls from the parser to the sink, 
 *    create a content sink as usual and hand it to the parser.
 *  
 *    If you want to use a normal sink AND simultaneously have a
 *    parse-log generated, you can set an environment variable
 *    and a logging sink will be created. It will act as a proxy
 *    to the REAL sink you are using after it logs the call. This
 *    form of the loggingsink is constructed using the version
 *    that accepts an nsIHTMLContentSink*.
 *
 * Contributor(s): 
 */
#include "nsLoggingSink.h"
#include "nsHTMLTags.h"
#include "nsString.h"
#include "prprf.h"

static NS_DEFINE_IID(kIContentSinkIID, NS_ICONTENT_SINK_IID);
static NS_DEFINE_IID(kIHTMLContentSinkIID, NS_IHTML_CONTENT_SINK_IID);
static NS_DEFINE_IID(kILoggingSinkIID, NS_ILOGGING_SINK_IID);
static NS_DEFINE_IID(kISupportsIID, NS_ISUPPORTS_IID);

// list of tags that have skipped content
static char gSkippedContentTags[] = {
  eHTMLTag_style,
  eHTMLTag_script,
  eHTMLTag_server,
  eHTMLTag_textarea,
  eHTMLTag_title,
  0
};


nsresult
NS_NewHTMLLoggingSink(nsIContentSink** aInstancePtrResult)
{
  NS_PRECONDITION(nsnull != aInstancePtrResult, "null ptr");
  if (nsnull == aInstancePtrResult) {
    return NS_ERROR_NULL_POINTER;
  }
  nsLoggingSink* it = new nsLoggingSink();
  if (nsnull == it) {
    return NS_ERROR_OUT_OF_MEMORY;
  }
  return it->QueryInterface(kIContentSinkIID, (void**) aInstancePtrResult);
}

nsLoggingSink::nsLoggingSink() {
  NS_INIT_REFCNT();
  mOutput = 0;
	mLevel=-1;
  mSink=0;
}

nsLoggingSink::~nsLoggingSink() { 
  mSink=0;
  if(mOutput && mAutoDeleteOutput) {
    delete mOutput;
  }
  mOutput=0;
}

NS_IMPL_ADDREF(nsLoggingSink)
NS_IMPL_RELEASE(nsLoggingSink)

nsresult
nsLoggingSink::QueryInterface(const nsIID& aIID, void** aInstancePtr)
{
  NS_PRECONDITION(nsnull != aInstancePtr, "null ptr");
  if (nsnull == aInstancePtr) {                                            
    return NS_ERROR_NULL_POINTER;                                        
  }                                                                      
  if (aIID.Equals(kISupportsIID)) {
    nsISupports* tmp = this;
    *aInstancePtr = (void*) tmp;
  }
  else if (aIID.Equals(kIContentSinkIID)) {
    nsIContentSink* tmp = this;
    *aInstancePtr = (void*) tmp;
  }
  else if (aIID.Equals(kIHTMLContentSinkIID)) {
    nsIHTMLContentSink* tmp = this;
    *aInstancePtr = (void*) tmp;
  }
  else if (aIID.Equals(kILoggingSinkIID)) {
    nsILoggingSink* tmp = this;
    *aInstancePtr = (void*) tmp;
  }
  else {
    *aInstancePtr = nsnull;
    return NS_NOINTERFACE;
  }
  NS_ADDREF(this);
  return NS_OK;
}

NS_IMETHODIMP
nsLoggingSink::SetOutputStream(PRFileDesc *aStream,PRBool autoDeleteOutput) {
  mOutput = aStream;
  mAutoDeleteOutput=autoDeleteOutput;
  return NS_OK;
}

static
void WriteTabs(PRFileDesc * out,int aTabCount) {
  int tabs;
  for(tabs=0;tabs<aTabCount;tabs++)
    PR_fprintf(out, "  ");
}


NS_IMETHODIMP
nsLoggingSink::WillBuildModel() {
  
  WriteTabs(mOutput,++mLevel);
  PR_fprintf(mOutput, "<begin>\n");
  
  //proxy the call to the real sink if you have one.
  if(mSink) {
    mSink->WillBuildModel();
  }

  return NS_OK;
}

NS_IMETHODIMP
nsLoggingSink::DidBuildModel(PRInt32 aQualityLevel) {
  
  WriteTabs(mOutput,mLevel--);
  PR_fprintf(mOutput, "</begin>\n");

  //proxy the call to the real sink if you have one.
  nsresult theResult=NS_OK;
  if(mSink) {
    theResult=mSink->DidBuildModel(aQualityLevel);
  }

  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::WillInterrupt() {
  nsresult theResult=NS_OK;

  //proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->WillInterrupt();
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::WillResume() {
  nsresult theResult=NS_OK;

  //proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->WillResume();
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::SetParser(nsIParser* aParser)  {
  nsresult theResult=NS_OK;

  //proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->SetParser(aParser);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::OpenContainer(const nsIParserNode& aNode) {

  OpenNode("container", aNode); //do the real logging work...

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->OpenContainer(aNode);
  }
  
  return theResult;

}

NS_IMETHODIMP
nsLoggingSink::CloseContainer(const nsIParserNode& aNode) {

  nsresult theResult=NS_OK;

  nsHTMLTag nodeType = nsHTMLTag(aNode.GetNodeType());
  if ((nodeType >= eHTMLTag_unknown) &&
      (nodeType <= nsHTMLTag(NS_HTML_TAG_MAX))) {
    const char* tag = nsHTMLTags::GetStringValue(nodeType);
		theResult=CloseNode(tag);
	}
	else theResult= CloseNode("???");

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->CloseContainer(aNode);
  }
  
  return theResult;

}

NS_IMETHODIMP
nsLoggingSink::AddLeaf(const nsIParserNode& aNode) {
  LeafNode(aNode);

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->AddLeaf(aNode);
  }
  
  return theResult;

} 


NS_IMETHODIMP 
nsLoggingSink::NotifyError(const nsParserError* aError) {
  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->NotifyError(aError);
  }
  
  return theResult;
}


/**
 *  This gets called by the parser when you want to add
 *  a PI node to the current container in the content
 *  model.
 *  
 *  @updated gess 3/25/98
 *  @param   
 *  @return  
 */
NS_IMETHODIMP
nsLoggingSink::AddProcessingInstruction(const nsIParserNode& aNode){

#ifdef VERBOSE_DEBUG
  DebugDump("<",aNode.GetText(),(mNodeStackPos)*2);
#endif

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->AddProcessingInstruction(aNode);
  }
  
  return theResult;
}

/**
 *  This gets called by the parser when it encounters
 *  a DOCTYPE declaration in the HTML document.
 */

NS_IMETHODIMP
nsLoggingSink::AddDocTypeDecl(const nsIParserNode& aNode, PRInt32 aMode) {

#ifdef VERBOSE_DEBUG
  DebugDump("<",aNode.GetText(),(mNodeStackPos)*2);
#endif

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->AddDocTypeDecl(aNode,aMode);
  }
  
  return theResult;

}

/**
 *  This gets called by the parser when you want to add
 *  a comment node to the current container in the content
 *  model.
 *  
 *  @updated gess 3/25/98
 *  @param   
 *  @return  
 */
NS_IMETHODIMP
nsLoggingSink::AddComment(const nsIParserNode& aNode){

#ifdef VERBOSE_DEBUG
  DebugDump("<",aNode.GetText(),(mNodeStackPos)*2);
#endif

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->AddComment(aNode);
  }
  
  return theResult;

}


NS_IMETHODIMP
nsLoggingSink::SetTitle(const nsString& aValue) {
   
   nsAutoString tmp;
   QuoteText(aValue, tmp);
   WriteTabs(mOutput,++mLevel);
   PR_fprintf(mOutput, "<title value=\"%s\"/>\n", tmp.GetBuffer());
   --mLevel;
  
  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->SetTitle(aValue);
  }
  
  return theResult;

}


NS_IMETHODIMP
nsLoggingSink::OpenHTML(const nsIParserNode& aNode) {
  OpenNode("html", aNode);

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->OpenHTML(aNode);
  }
  
  return theResult;

}

NS_IMETHODIMP
nsLoggingSink::CloseHTML(const nsIParserNode& aNode) {
  CloseNode("html");

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->CloseHTML(aNode);
  }
  
  return theResult;

}

NS_IMETHODIMP
nsLoggingSink::OpenHead(const nsIParserNode& aNode) {
  OpenNode("head", aNode);

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->OpenHead(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::CloseHead(const nsIParserNode& aNode) {
  CloseNode("head");

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->CloseHead(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::OpenBody(const nsIParserNode& aNode) {
  OpenNode("body", aNode);

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->OpenBody(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::CloseBody(const nsIParserNode& aNode) {
  CloseNode("body");

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->CloseBody(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::OpenForm(const nsIParserNode& aNode) {
  OpenNode("form", aNode);

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->OpenForm(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::CloseForm(const nsIParserNode& aNode) {
  CloseNode("form");

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->CloseForm(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::OpenMap(const nsIParserNode& aNode) {
  OpenNode("map", aNode);

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->OpenMap(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::CloseMap(const nsIParserNode& aNode) {
  CloseNode("map");

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->CloseMap(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::OpenFrameset(const nsIParserNode& aNode) {
  OpenNode("frameset", aNode);

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->OpenFrameset(aNode);
  }
  
  return theResult;
}

NS_IMETHODIMP
nsLoggingSink::CloseFrameset(const nsIParserNode& aNode) {
  CloseNode("frameset");

  nsresult theResult=NS_OK;

  //then proxy the call to the real sink if you have one.
  if(mSink) {
    theResult=mSink->CloseFrameset(aNode);
  }
  
  return theResult;
}


nsresult
nsLoggingSink::OpenNode(const char* aKind, const nsIParserNode& aNode) {
	WriteTabs(mOutput,++mLevel);

  PR_fprintf(mOutput,"<open container=");

  nsHTMLTag nodeType = nsHTMLTag(aNode.GetNodeType());
  if ((nodeType >= eHTMLTag_unknown) &&
      (nodeType <= nsHTMLTag(NS_HTML_TAG_MAX))) {
    const char* tag = nsHTMLTags::GetStringValue(nodeType);
    PR_fprintf(mOutput, "\"%s\"", tag);
  }
  else {
    const nsAReadableString& theText = aNode.GetText();
    PR_fprintf(mOutput, "\"%s\"", theText);

  }

  if (WillWriteAttributes(aNode)) {
    PR_fprintf(mOutput, ">\n");
    WriteAttributes(aNode);
    PR_fprintf(mOutput, "</open>\n");
  }
  else {
    PR_fprintf(mOutput, ">\n");
  }

  return NS_OK;
}

nsresult
nsLoggingSink::CloseNode(const char* aKind) {
	WriteTabs(mOutput,mLevel--);
  PR_fprintf(mOutput, "<close container=\"%s\">\n", aKind);
  return NS_OK;
}


nsresult
nsLoggingSink::WriteAttributes(const nsIParserNode& aNode) {
  nsAutoString tmp, tmp2;
  PRInt32 ac = aNode.GetAttributeCount();
  for (PRInt32 i = 0; i < ac; i++) {
    const nsAReadableString& k = aNode.GetKeyAt(i);
    const nsString& v = aNode.GetValueAt(i);

		PR_fprintf(mOutput, " <attr key=\"%s\" value=\"", k);
 
    tmp.Truncate();
    tmp.Append(v);

    if(0<tmp.Length()) {
      PRUnichar first = tmp.First();
      if ((first == '"') || (first == '\'')) {
        if (tmp.Last() == first) {
          tmp.Cut(0, 1);
          PRInt32 pos = tmp.Length() - 1;
          if (pos >= 0) {
            tmp.Cut(pos, 1);
          }
        } else {
          // Mismatched quotes - leave them in
        }
      }
    }    
    QuoteText(tmp, tmp2);
		PR_fprintf(mOutput, "%s\"/>\n", tmp2.GetBuffer());
  }

  if (0 != strchr(gSkippedContentTags, aNode.GetNodeType())) {
    const nsString& content = aNode.GetSkippedContent();
    if (content.Length() > 0) {
      QuoteText(content, tmp);
      PR_fprintf(mOutput, " <content value=\"");
      PR_fprintf(mOutput, "%s\"/>\n", tmp.GetBuffer()) ;
    }
  }

  return NS_OK;
}

PRBool
nsLoggingSink::WillWriteAttributes(const nsIParserNode& aNode)
{
  PRInt32 ac = aNode.GetAttributeCount();
  if (0 != ac) {
    return PR_TRUE;
  }
  if (0 != strchr(gSkippedContentTags, aNode.GetNodeType())) {
    const nsString& content = aNode.GetSkippedContent();
    if (content.Length() > 0) {
      return PR_TRUE;
    }
  }
  return PR_FALSE;
}

nsresult
nsLoggingSink::LeafNode(const nsIParserNode& aNode)
{
 	WriteTabs(mOutput,1+mLevel);
	nsHTMLTag				nodeType  = nsHTMLTag(aNode.GetNodeType());

  if ((nodeType >= eHTMLTag_unknown) &&
      (nodeType <= nsHTMLTag(NS_HTML_TAG_MAX))) {
    const char* tag = nsHTMLTags::GetStringValue(nodeType);

		if(tag)
      PR_fprintf(mOutput, "<leaf tag=\"%s\"", tag);
    else PR_fprintf(mOutput, "<leaf tag=\"???\"");

    if (WillWriteAttributes(aNode)) {
			PR_fprintf(mOutput, ">\n");
      WriteAttributes(aNode);
			PR_fprintf(mOutput, "</leaf>\n");
    }
    else {
			PR_fprintf(mOutput, "/>\n");
    }
  }
  else {
    PRInt32 pos;
    nsAutoString tmp;
    switch (nodeType) {
			case eHTMLTag_whitespace:
			case eHTMLTag_text:
				QuoteText(aNode.GetText(), tmp);
				PR_fprintf(mOutput, "<text value=\"%s\"/>\n", tmp.GetBuffer());
				break;

			case eHTMLTag_newline:
				PR_fprintf(mOutput, "<newline/>\n");
				break;

			case eHTMLTag_entity:
				tmp.Append(aNode.GetText());
				tmp.Cut(0, 1);
				pos = tmp.Length() - 1;
				if (pos >= 0) {
					tmp.Cut(pos, 1);
				}
				PR_fprintf(mOutput, "<entity value=\"%s\"/>\n", tmp.GetBuffer());
				break;

			default:
				NS_NOTREACHED("unsupported leaf node type");
		}//switch
  }
  return NS_OK;
}

nsresult 
nsLoggingSink::QuoteText(const nsAReadableString& aValue, nsString& aResult) {
  aResult.Truncate();
  const PRUnichar* cp = nsPromiseFlatString(aValue);
  const PRUnichar* end = cp + aValue.Length();
  while (cp < end) {
    PRUnichar ch = *cp++;
    if (ch == '"') {
      aResult.AppendWithConversion("&quot;");
    }
    else if (ch == '&') {
      aResult.AppendWithConversion("&amp;");
    }
    else if ((ch < 32) || (ch >= 127)) {
      aResult.AppendWithConversion("&#");
      aResult.AppendInt(PRInt32(ch), 10);
      aResult.AppendWithConversion(';');
    }
    else {
      aResult.Append(ch);
    }
  }
  return NS_OK;
}

NS_IMETHODIMP
nsLoggingSink::DoFragment(PRBool aFlag) 
{
  return NS_OK; 
}

/**
 * This gets called when handling illegal contents, especially
 * in dealing with tables. This method creates a new context.
 * 
 * @update 04/04/99 harishd
 * @param aPosition - The position from where the new context begins.
 */
NS_IMETHODIMP
nsLoggingSink::BeginContext(PRInt32 aPosition) 
{
  return NS_OK;
}

/**
 * This method terminates any new context that got created by
 * BeginContext and switches back to the main context.  
 *
 * @update 04/04/99 harishd
 * @param aPosition - Validates the end of a context.
 */
NS_IMETHODIMP
nsLoggingSink::EndContext(PRInt32 aPosition)
{
  return NS_OK;
}
