package docspell.joex.process

import cats.effect._

import docspell.backend.ops.OItem
import docspell.common.ProcessItemArgs
import docspell.ftsclient.FtsClient
import docspell.joex.Config
import docspell.joex.scheduler.Task

object ProcessItem {

  def apply[F[_]: ConcurrentEffect: ContextShift](
      cfg: Config,
      itemOps: OItem[F],
      fts: FtsClient[F]
  )(item: ItemData): Task[F, ProcessItemArgs, ItemData] =
    ExtractArchive(item)
      .flatMap(Task.setProgress(20))
      .flatMap(processAttachments0(cfg, fts, (40, 60, 80)))
      .flatMap(LinkProposal[F])
      .flatMap(SetGivenData[F](itemOps))
      .flatMap(Task.setProgress(99))

  def processAttachments[F[_]: ConcurrentEffect: ContextShift](
      cfg: Config,
      fts: FtsClient[F]
  )(item: ItemData): Task[F, ProcessItemArgs, ItemData] =
    processAttachments0[F](cfg, fts, (30, 60, 90))(item)

  def analysisOnly[F[_]: Sync](
      cfg: Config
  )(item: ItemData): Task[F, ProcessItemArgs, ItemData] =
    TextAnalysis[F](cfg.textAnalysis)(item)
      .flatMap(FindProposal[F](cfg.processing))
      .flatMap(EvalProposals[F])
      .flatMap(SaveProposals[F])

  private def processAttachments0[F[_]: ConcurrentEffect: ContextShift](
      cfg: Config,
      fts: FtsClient[F],
      progress: (Int, Int, Int)
  )(item: ItemData): Task[F, ProcessItemArgs, ItemData] =
    ConvertPdf(cfg.convert, item)
      .flatMap(Task.setProgress(progress._1))
      .flatMap(TextExtraction(cfg.extraction, fts))
      .flatMap(Task.setProgress(progress._2))
      .flatMap(analysisOnly[F](cfg))
      .flatMap(Task.setProgress(progress._3))
}
