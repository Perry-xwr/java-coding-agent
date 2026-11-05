package com.agent;
import com.agent.memory.*;
import java.util.*;

/** Deterministic eligibility, not a model's assertion that its own proposal is safe. */
public final class MemoryQualityPolicy {
 public static boolean declaredPolicyOnly(String text){
  if(!safeSource(text)||!text.matches("(?s)^(以后|今后|后续|从现在起|之前|原来|此前).*"))return false;
  if(declaredRevisionOnly(text))return true;
  String whole=text.replaceFirst("[。.;；]+$","");if(lowRisk(whole))return true;
  var clauses=Arrays.stream(text.split("[，,。；;]")).map(String::strip).filter(s->!s.isEmpty()).toList();
  return !clauses.isEmpty()&&clauses.stream().allMatch(s->lowRisk(s.replaceFirst("^(?:之前规定|原来规定|此前规定|现在改成|现在改为)","")));
 }
 private static boolean declaredRevisionOnly(String text){
  var matcher=java.util.regex.Pattern.compile("(?is)^(?:之前|原来|此前)(?:规定|决定|约定)?(.+?)[，,。；;]?(?:现在|今后|以后)(?:改成|改为|替换为)(.+?)[。.;；;]*$").matcher(text.strip());
  if(!matcher.matches())return false;
  String oldValue=matcher.group(1).strip(),newValue=matcher.group(2).strip();
  return lowRisk(oldValue)&&lowRisk(newValue);
 }
 public static boolean safeSource(String text){
  // Whole-source privacy gates (especially credential detection) run before
  // any semantic request. Untrusted examples, questions and local no-memory
  // statements are filtered at evidence-span scope below.
  if(text==null||text.length()>2048||wholeMessageProhibited(text))return false;
  try{LongTermMemory.validateContent(text.replace('\n',' ').replace('\r',' '));return true;}catch(IllegalArgumentException unsafe){return false;}
 }
 public static boolean wholeMessageProhibited(String text){
  if(text==null)return false;
  String normalized=text.replaceAll("\\s+"," ").strip();
  String scope="(?:整条消息|整段(?:话|内容|消息)|这条消息|本条消息|以上(?:所有)?内容|全部(?:消息|内容|信息)|任何内容)";
  String denial="(?:不要|不需要|无需|不用|别)(?:再)?(?:记住|保存|记录|记忆)";
  return normalized.matches("(?is).*"+scope+".{0,24}"+denial+".*")
          ||normalized.matches("(?is).*"+denial+".{0,12}(?:把)?"+scope+".*")
          ||normalized.matches("(?is).*(?:整条消息|整段(?:话|内容|消息)|全部内容|以上内容).{0,12}(?:都|全部|一律).{0,12}"+denial+".*");
 }
 static List<String> sentenceSegments(String text){
  if(text==null||text.isBlank())return List.of();
  return Arrays.stream(text.split("(?<=[。！？!?；;\\r\\n])")).map(String::strip).filter(s->!s.isEmpty()).toList();
 }
 static boolean locallyProhibited(String segment){return segment!=null&&segment.matches("(?is).*(?:不需要|不要|无需|不用|别)(?:(?:再|长期|继续)|把.{0,4})*(?:记住|保存|记录|记忆).*");}
 static boolean temporarySegment(String segment){return segment!=null&&segment.matches("(?is).*(?:今天|今日|刚才|刚刚|这次|这轮|本轮|临时|暂时|暂时性|this time|today|temporary|temporarily).*");}
 private static boolean untrustedContinuationLead(String segment){return segment!=null&&segment.matches("(?is).*(?:下面只是|这只是|例如|假设|假想|虚构|引用).*(?:示例|例子|内容|说法|文本).*|.*不代表.{0,12}真实.*");}
 private static boolean untrustedOrNonMemorySegment(String segment){return segment!=null&&(
         segment.matches("(?is).*[?？\"“”「」『』].*")
         ||segment.matches("(?is).*(历史|引用|网页|工具输出|文档内容|翻译|假设|假想|虚构|不代表.{0,12}真实|仅供.{0,8}(?:测试|参考)|下面只是.{0,12}(?:示例|例子)|忽略|绕过|关闭.*审批|未经授权|ignore|bypass|测试通过|已完成).*")
         ||segment.matches("(?is)^\\s*(?:请(?:你)?(?:解释|说明|介绍|分析|总结|查找|读取|搜索)|帮我(?:解释|说明|介绍|分析|总结|查找|读取|搜索)|解释(?:一下|说明)?|说明(?:一下)?|介绍(?:一下)?|分析(?:一下)?|总结(?:一下)?|查询|搜索|读取|what\\s|how\\s|please\\s+(?:explain|summarize|analyze|find)).*"));}
 public static List<String> semanticSegments(String text){
  if(!safeSource(text)||wholeMessageProhibited(text))return List.of();
  var eligible=new ArrayList<String>();
  boolean suppressNextUntrustedExample=false;
  for(String sentence:sentenceSegments(text)){
   if(suppressNextUntrustedExample){suppressNextUntrustedExample=false;continue;}
   if(untrustedContinuationLead(sentence)){suppressNextUntrustedExample=true;continue;}
   if(sentence.matches("(?is)^\\s*(?:这轮|本轮|这次|今天|今日|刚才|刚刚|this time|today|temporarily|temporary).*"))continue;
   if(!locallyProhibited(sentence)&&!temporarySegment(sentence)&&!untrustedOrNonMemorySegment(sentence)){eligible.add(sentence);continue;}
   // A local ban or temporary event should suppress its own clause, not an
   // adjacent durable preference in the same natural-language sentence.
   for(String clause:sentence.split("(?<=[，,])")){
    String part=clause.strip();if(!part.isEmpty()&&!locallyProhibited(part)&&!temporarySegment(part)&&!untrustedOrNonMemorySegment(part))eligible.add(part);
   }
  }
  return List.copyOf(eligible);
 }
 public static boolean evidenceAllowed(String evidence,String source){
  if(evidence==null||evidence.isBlank()||source==null||!source.contains(evidence))return false;
  return semanticSegments(source).stream().anyMatch(segment->segment.contains(evidence));
 }
 /** Agent-managed writes require an exact, complete span from the current user's durable statement. */
 public static boolean agentManagedProposalAllowed(String content,String category,String source){
  if(!List.of("PREFERENCE","PROJECT_CONSTRAINT").contains(category)||content==null||source==null
      ||!safeSource(source)||wholeMessageProhibited(source)||!hasDurableIntentCue(source)
      ||!completeCandidate(content)||!source.contains(content)||!entailed(content,source))return false;
  // The proposal must remain one exact contiguous source span (checked above),
  // but that span may consist of several adjacent complete sentences. Validate
  // each sentence independently so a safe multi-sentence preference is not
  // rejected merely because sentence splitting divided it. A sentence that was
  // filtered as temporary, prohibited, quoted, or otherwise unsafe still fails.
  List<String> eligibleSegments=semanticSegments(source);
  List<String> proposedSentences=sentenceSegments(content);
  boolean eligible=!proposedSentences.isEmpty()
      &&proposedSentences.stream().allMatch(segment->eligibleSegments.contains(segment)
          &&hasDurableIntentCue(segment));
  if(!eligible||temporarySegment(content)||untrustedOrNonMemorySegment(content))return false;
  String normalized=content.replaceAll("\\s+","");
  if(category.equals("PREFERENCE")&&!normalized.matches("(?is).*(?:偏好|习惯|希望|更喜欢|倾向|prefer|preference).*"))return false;
  if(category.equals("PROJECT_CONSTRAINT")&&!normalized.matches("(?is).*(?:项目|仓库|代码库|project|repository).*(?:约定|规定|统一|使用|采用|必须|should|must|use).*"))return false;
  try{LongTermMemory.validateContent(content);return true;}catch(IllegalArgumentException unsafe){return false;}
 }
 /** Reject dangling context headers without inventing the missing action. Historical records remain readable. */
 public static boolean completeCandidate(String content){
  if(content==null||content.isBlank())return false;
  String text=content.strip().replaceFirst("[。；;，,：:]+$", "");
  return !text.matches("(?s).*(?:时|之前|之后|前|关于|如下|偏好|约定)$");
 }
 /** Classification changes only; content/evidence remain exact original spans. */
 public static MemoryExtractor.Proposal classifyCandidate(MemoryExtractor.Proposal p,MemoryExtractor.Source source){
  String content=p.proposedContent();
  boolean communication=completeCandidate(content)&&evidenceAllowed(p.evidenceQuote(),source.userText())
      &&hasDurableIntentCue(source.userText())
      &&content.matches("(?s).*(?:讨论|讲解|解释|回答|沟通).*先.+再.+");
  return communication?new MemoryExtractor.Proposal(content,"PREFERENCE",p.sourceMessageRefs(),p.evidenceQuote(),p.confidence(),p.proposedAction()):p;
 }
 /**
  * A semantic preference may use the automatic path only when its content is
  * itself an exact, sentence-bounded user quote and the quoted source span is
  * eligible. The model's category/confidence alone never grants persistence.
  */
 static boolean semanticPreferenceAutoEligible(String content,String evidence,String source){
  if(!completeCandidate(content)||evidence==null||source==null||!evidenceAllowed(evidence,source)
      ||!evidence.contains(content)||!entailed(content,source))return false;
  if(content.matches("(?is).*(?:password|passphrase|api\\s*key|secret|token|credential|medical|diagnos|medication|financial|bank|法律意见|病史|医疗|诊断|药物|银行卡|财务|投资|法律建议).*"))return false;
  try{LongTermMemory.validateContent(content);return true;}catch(IllegalArgumentException unsafe){return false;}
 }
 private static boolean naturalLanguageCandidate(String text){
  if(!safeSource(text)||wholeMessageProhibited(text))return false;
  String active=String.join(" ",semanticSegments(text));
  if(active.matches("(?is)^(?:请(?:你)?(?:解释|说明|介绍|分析|总结|查找|读取|搜索)|帮我(?:解释|说明|介绍|分析|总结|查找|读取|搜索)|解释(?:一下|说明)?|说明(?:一下)?|介绍(?:一下)?|分析(?:一下)?|总结(?:一下)?|查询|搜索|读取|what\\s|how\\s|please\\s+(?:explain|summarize|analyze|find)).*"))return false;
  int visible=(int)active.codePoints().filter(cp->!Character.isWhitespace(cp)).count();
  int letters=(int)active.codePoints().filter(Character::isLetter).count();
  if(visible<14||letters<8||letters/(double)visible<0.50)return false;
  return !active.matches("(?is).*(?:```|\\b(?:Exception|ERROR|WARN|INFO|TRACE)\\b|\\bat\\s+[\\w.$]+\\().*");
 }
 public static boolean durable(String text){return naturalLanguageCandidate(text);}
 static String meaning(String text){
  if(text==null||negatesProcedurePreference(text))return "";
  String s=AutomaticMemory.canonical(text).replaceFirst("^(?:我们|我)(?:(?:以后|今后|后续|长期))?(?:约定|决定|明确规定|确认|计划)","")
          .replaceAll("[。.,，：:；;]","");
  String compact=s.replaceAll("\\s+","");
  if(s.matches("(?i)(?:项目(?:统一)?使用)?(?:java|jdk)\\d+"))return "java:"+s.replaceAll("\\D","");
  if(s.matches("(?:项目(?:统一)?使用)?maven"))return "build:maven";
  if(s.matches("(?:项目(?:统一)?使用)?(?:java|jdk)\\d+(?:和|与|及)maven"))return "stack:"+s;
  if(s.matches("(?:增加|新增|开发)功能前(?:必须)?先(?:分析|评估)风险"))return "risk:before-feature";
  if(s.matches("(?:不要|不得|禁止)(?:随便|擅自|自动)?(?:创建|新增|生成)(?:markdown|md)文档"))return "markdown:no-unsolicited-docs";
  if(s.matches("(?:讲|讲解)(?:大模型算法|算法|技术)(?:时)?先讲原理再讲代码最后再做面试题"))return "learning:principle-code-interview";
  if(compact.matches("(?s).*(?:讨论新功能|新功能讨论|新增功能方案).*(?:两个|两种|2)(?:个)?(?:可行)?方案.*(?:比较|对比).*(?:各自)?(?:的)?(?:优缺点|利弊).*(?:再)?(?:推荐|建议).*"))return "workflow:compare-and-recommend";
  if(s.matches("(?:引入|增加|选择|使用)第三方依赖前先(?:判断|评估|确认)(?:java\\d+)?jdk原生能力(?:是否)?(?:足够|可用|满足需求)"))return "dependency:check-jdk-native-first";
  if(compact.matches("(?s).*(?:做|进行)?性能(?:优化|调优)前.*(?:先)?(?:通过)?(?:实际测量|实际测试|实测|基准测试|profiling).*(?:定位|确认|判断|找出)(?:实际)?瓶颈.*"))return "performance:measure-before-optimizing";
  if(s.matches("(?:学习新技术时|学习新技术)先(?:给|看|做)?最小可运行示例再逐步扩展"))return "learning:minimal-example-first";
  if(weeklyRoutine(compact))return "schedule:"+compact;
  if(s.matches("(?:我)?偏好(?:中文|英文)(?:回答|回复)?"))return "language:"+(s.contains("中文")?"zh":"en");
  if(s.matches("(?:我)?偏好(?:简洁|详细)(?:回答|回复)?"))return "detail:"+(s.contains("简洁")?"short":"long");
  return "";
 }
 private static String domain(String text){
  String known=meaning(text);int separator=known.indexOf(':');if(separator>0)return known.substring(0,separator);
  String compact=text==null?"":text.replaceAll("\\s+","");
  // Keep strongly scoped communication preferences independent from explicit
  // project facts such as a Java version, while leaving same-domain conflicts
  // under the existing conservative review path.
  if(compact.matches("(?s).*(?:沟通偏好|回答偏好|讲解偏好|解释偏好|复杂问题).*(?:先给结论|先说结论|理由和步骤|生活化例子|最小可运行示例).*"))return "communication";
  return "";
 }
 private static boolean weeklyRoutine(String compact){
  var matcher=java.util.regex.Pattern.compile("(?:周|星期)[一二三四五六日天]").matcher(compact);
  var days=new HashSet<String>();while(matcher.find())days.add(matcher.group());
  // Recognize the schedule structure from repeated weekday markers. The semantic
  // candidate still needs an explicit affirmative durable assertion in its exact
  // source evidence before it can use the automatic path.
  return days.size()>=2;
 }
 /**
  * Allows a semantic extractor to omit only an explicit user assertion lead-in
  * (for example "我们约定") from its proposed content. The exact evidence span
  * must still occur in an eligible user-authored segment, and the omitted prefix
  * must itself be an affirmative durable-intent statement.
  */
 static boolean groundedDurableCandidate(String content,String evidence,String source){
  boolean weekly=content!=null&&meaning(content).startsWith("schedule:");
  if(weekly&&!hasExplicitDurableAssertion(evidence))return false;
  if(entailed(content,source))return true;
  if(content==null||content.isBlank()||!evidenceAllowed(evidence,source)||!evidence.contains(content))return false;
  int at=-1;
  while((at=evidence.indexOf(content,at+1))>=0){
   String prefix=evidence.substring(0,at).replaceAll("\\s+","");
   if(prefix.matches("(?is)(?:我|我们)(?:(?:以后|今后|后续|长期))?(?:约定|决定|明确规定|确认|计划|偏好|希望|更喜欢|倾向于)"
           +"|(?:以后|今后|后续|长期)(?:我|我们)?(?:约定|决定|明确规定|确认|计划|偏好|希望|更喜欢|倾向于)"))return true;
  }
  return false;
 }
 private static boolean hasExplicitDurableAssertion(String evidence){
  if(evidence==null)return false;
  String compact=evidence.replaceAll("\\s+","");
  return compact.matches("(?is)^(?:我|我们)(?:(?:以后|今后|后续|长期))?(?:明确)?(?:约定|决定|确认|规定|计划|偏好|希望|更喜欢|倾向于).*"
          +"|^(?:以后|今后|后续|长期)(?:我|我们)?(?:明确)?(?:约定|决定|确认|规定|计划|偏好|希望|更喜欢|倾向于).*" );
 }
 private static boolean negatesProcedurePreference(String text){
  String normalized=text.replaceAll("\\s+","");
  return normalized.matches("(?is).*(?:不希望|不想|不需要|不必|不要|禁止|避免).{0,24}(?:给出|提供|比较|对比|推荐|实测|测量|基准测试|定位瓶颈).*")
      ||normalized.matches("(?is).*(?:讨论新功能|新功能讨论).{0,16}(?:不希望|不想|不要|不需要|不必).{0,20}(?:方案|比较|推荐).*")
      ||normalized.matches("(?is).*性能(?:优化|调优)前.{0,16}(?:不要|不需要|不必|避免).{0,20}(?:实测|测量|基准测试|定位瓶颈).*");
 }
 public static boolean lowRisk(String text){return !meaning(text).isEmpty();}
 public static boolean deterministicLowRisk(String text){return lowRisk(text)&&!meaning(text).startsWith("schedule:");}
 static boolean entailed(String content,String source){
  if(!safeSource(source))return false;
  if(content==null||content.isBlank())return false;
  int from=0;
  while((from=source.indexOf(content,from))>=0){
   int end=from+content.length();
   boolean start=from==0||sentenceBoundary(source.charAt(from-1))||revisionPrefix(source,from);
   // A quote that includes its terminal punctuation is already sentence-bounded
   // even when another sentence follows immediately without whitespace.
   boolean finish=end==source.length()||sentenceBoundary(source.charAt(end))
       ||sentenceBoundary(source.charAt(end-1));
   if(start&&finish)return true;
   from++;
  }
  return false;
 }
 private static boolean sentenceBoundary(char c){return "。.;；;，,：:".indexOf(c)>=0||c=='\r'||c=='\n';}
 private static boolean revisionPrefix(String source,int from){String prefix=source.substring(0,from);return prefix.endsWith("现在改成")||prefix.endsWith("现在改为")||prefix.endsWith("替换为");}
 static boolean semanticNeeded(MemoryExtractor.Source source){
   String text=source.userText();if(!durable(text))return false;
   var rules=new DeterministicMemoryExtractor().extract(List.of(source)).stream()
           .filter(p->AutomaticMemory.lowRisk(p.proposedContent())).toList();
   // Semantic assistance is a fallback for a user's durable preference or decision,
   // not a second model call for every ordinary task phrased in natural language.
   // The cue set is intentionally broader than the deterministic extractor's exact
   // templates, while requiring some signal of persistence or user preference.
   if(rules.isEmpty())return hasDurableIntentCue(text)&&!semanticSegments(text).isEmpty();
   for(String segment:semanticSegments(text)){
    String normalized=segment.replaceFirst("[。；;!?！？]+$","").strip();
    if(rules.stream().anyMatch(p->p.proposedContent().equals(normalized)))continue;
    boolean allClausesCovered=true;
    for(String clause:normalized.split("[，,]")){
     String unit=clause.strip();if(unit.isEmpty())continue;
     if(rules.stream().noneMatch(p->p.proposedContent().equals(unit)||unit.contains(p.proposedContent()))){allClausesCovered=false;break;}
    }
    if(!allClausesCovered)return true;
   }
   return false;
 }
 private static boolean hasDurableIntentCue(String text){
   if(text.indexOf('?')>=0||text.indexOf('？')>=0)return false;
   return text.matches("(?is).*(?:以后|今后|后续|长期|长期习惯|长期偏好|习惯|偏好|约定|决定|原则|之前|原来|此前|现在改成|现在改为|替换为|"
       +"我希望|我更喜欢|我倾向于|我倾向|每次|通常|始终|总是|统一采用|统一使用|"
       +"from now on|in the future|long.term|preference|convention|we decided|i prefer|i'd prefer).*");
 }
 static ConflictDetector.Relation relation(String text,List<LongTermMemory> entries){
  String meaning=meaning(text);
  for(var m:entries)if(AutomaticMemory.canonical(m.content()).equals(AutomaticMemory.canonical(text))||!meaning.isEmpty()&&meaning.equals(meaning(m.content())))return ConflictDetector.Relation.DUPLICATE;
  var old=new DeterministicConflictDetector().detect(text,entries).relation();
  if(old==ConflictDetector.Relation.POSSIBLE_CONFLICT||old==ConflictDetector.Relation.DUPLICATE)return old;
  if(entries.isEmpty())return ConflictDetector.Relation.NEW;
  String candidateDomain=domain(text);
  if(!candidateDomain.isEmpty()&&entries.stream().allMatch(entry->{String existingDomain=domain(entry.content());return !existingDomain.isEmpty()&&!existingDomain.equals(candidateDomain);}))return ConflictDetector.Relation.RELATED;
  if(meaning.isEmpty())return old;
  for(var entry:entries){String existing=meaning(entry.content());if(existing.isEmpty())return ConflictDetector.Relation.UNCERTAIN;
   String a=existing.split(":")[0],b=meaning.split(":")[0];
   if(a.equals(b)||a.equals("stack")&&Set.of("java","build").contains(b)||b.equals("stack")&&Set.of("java","build").contains(a))return ConflictDetector.Relation.POSSIBLE_CONFLICT;
  }return ConflictDetector.Relation.RELATED;
 }
 static boolean exactRevision(String source,LongTermMemory old,String replacement){
  if(meaning(old.content()).isEmpty()||meaning(replacement).isEmpty()||!meaning(old.content()).split(":")[0].equals(meaning(replacement).split(":")[0]))return false;
  int a=source.indexOf(old.content()),b=source.indexOf(replacement);
  return a>=0&&b>a&&source.substring(0,a).matches("(?s).*(之前|原来|此前).*" )&&source.substring(a+old.content().length(),b).matches("(?s).*(现在|以后).*(改成|改为|替换为).*" );
 }
}
