package scala.meta.internal.metals

import java.net.URI
import java.nio.file.FileAlreadyExistsException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Range

class NewFilesProvider(
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    packageProvider: PackageProvider,
    focusedDocument: () => Option[AbsolutePath]
)(implicit
    ec: ExecutionContext
) {

  private val classPick = MetalsQuickPickItem(id = "class", label = "Class")
  private val caseClassPick =
    MetalsQuickPickItem(id = "case-class", label = "Case class")
  private val objectPick = MetalsQuickPickItem(id = "object", label = "Object")
  private val traitPick = MetalsQuickPickItem(id = "trait", label = "Trait")
  private val packageObjectPick =
    MetalsQuickPickItem(id = "package-object", label = "Package Object")
  private val worksheetPick =
    MetalsQuickPickItem(id = "worksheet", label = "Worksheet")
  private val ammonitePick =
    MetalsQuickPickItem(id = "ammonite", label = "Ammonite script")

  def handleFileCreation(
      directoryUri: Option[URI],
      name: Option[String],
      fileType: Option[String]
  ): Future[Unit] = {
    val directory = directoryUri
      .map { uri =>
        val path = uri.toString.toAbsolutePath
        if (path.isFile)
          path.parent
        else
          path
      }
      .orElse(focusedDocument().map(_.parent))

    val newlyCreatedFile =
      fileType match {
        case Some(ft) => createFile(directory, ft, name)
        case None =>
          askForKind
            .flatMapOption(createFile(directory, _, name))
      }

    newlyCreatedFile.map {
      case Some((path, cursorRange)) =>
        openFile(path, cursorRange)
      case None => ()
    }
  }

  private def createFile(
      directory: Option[AbsolutePath],
      fileType: String,
      name: Option[String]
  ) = {
    fileType match {
      case kind @ (classPick.id | caseClassPick.id | objectPick.id |
          traitPick.id) =>
        getName(kind, name)
          .mapOption(
            createClass(directory, _, kind)
          )
      case worksheetPick.id =>
        getName(worksheetPick.id, name)
          .mapOption(
            createEmptyFile(directory, _, ".worksheet.sc")
          )
      case ammonitePick.id =>
        getName(ammonitePick.id, name)
          .mapOption(
            createEmptyFile(directory, _, ".sc")
          )
      case packageObjectPick.id =>
        createPackageObject(directory).liftOption
      case invalid =>
        Future.failed(new IllegalArgumentException(invalid))
    }
  }

  private def askForKind: Future[Option[String]] = {
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          List(
            classPick,
            caseClassPick,
            objectPick,
            traitPick,
            packageObjectPick,
            worksheetPick,
            ammonitePick
          ).asJava,
          placeHolder = NewScalaFile.selectTheKindOfFileMessage
        )
      )
      .asScala
      .map {
        case kind if !kind.cancelled => Some(kind.itemId)
        case _ => None
      }
  }

  private def askForName(kind: String): Future[Option[String]] = {
    client
      .metalsInputBox(
        MetalsInputBoxParams(prompt = NewScalaFile.enterNameMessage(kind))
      )
      .asScala
      .map {
        case name if !name.cancelled => Some(name.value)
        case _ => None
      }
  }

  private def getName(
      kind: String,
      name: Option[String]
  ): Future[Option[String]] = {
    name match {
      case Some(v) if v.trim.length > 0 => Future.successful(name)
      case _ => askForName(kind)
    }
  }

  private def createClass(
      directory: Option[AbsolutePath],
      name: String,
      kind: String
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + ".scala")
    //name can be actually be "foo/Name", where "foo" is a folder to create
    val className = directory.getOrElse(workspace).resolve(name).filename
    val template = kind match {
      case caseClassPick.id => caseClassTemplate(className)
      case _ => classTemplate(kind, className)
    }
    val editText = template.map { s =>
      packageProvider
        .packageStatement(path)
        .map(_.fileContent)
        .getOrElse("") + s
    }
    createFileAndWriteText(path, editText)
  }

  private def createPackageObject(
      directory: Option[AbsolutePath]
  ): Future[(AbsolutePath, Range)] = {
    directory
      .map { directory =>
        val path = directory.resolve("package.scala")
        createFileAndWriteText(
          path,
          packageProvider
            .packageStatement(path)
            .getOrElse(NewFileTemplate.empty)
        )
      }
      .getOrElse(
        Future.failed(
          new IllegalArgumentException(
            "'directory' must be provided to create a package object"
          )
        )
      )
  }

  private def createEmptyFile(
      directory: Option[AbsolutePath],
      name: String,
      extension: String
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + extension)
    createFileAndWriteText(path, NewFileTemplate.empty)
  }

  private def createFileAndWriteText(
      path: AbsolutePath,
      template: NewFileTemplate
  ): Future[(AbsolutePath, Range)] = {
    val result = if (path.exists) {
      Future.failed(
        new FileAlreadyExistsException(s"The file $path already exists.")
      )
    } else {
      Future {
        path.writeText(template.fileContent)
        (path, template.cursorPosition.toLSP)
      }
    }
    result.failed.foreach {
      case NonFatal(e) => {
        scribe.error("Cannot create file", e)
        client.showMessage(
          MessageType.Error,
          s"Cannot create file:\n ${e.toString()}"
        )
      }
    }
    result
  }

  private def openFile(path: AbsolutePath, cursorRange: Range): Unit = {
    client.metalsExecuteClientCommand(
      new ExecuteCommandParams(
        ClientCommands.GotoLocation.id,
        List(
          new Location(path.toURI.toString(), cursorRange): Object
        ).asJava
      )
    )
  }

  private def classTemplate(kind: String, name: String): NewFileTemplate = {
    val indent = "  "
    NewFileTemplate(s"""|$kind $name {
                        |$indent@@
                        |}
                        |""".stripMargin)
  }

  private def caseClassTemplate(name: String): NewFileTemplate =
    NewFileTemplate(s"final case class $name(@@)")

}
