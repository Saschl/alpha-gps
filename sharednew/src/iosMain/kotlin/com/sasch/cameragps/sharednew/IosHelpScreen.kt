package com.sasch.cameragps.sharednew

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import cameragps.sharednew.generated.resources.Res
import cameragps.sharednew.generated.resources.arrow_back_24px
import cameragps.sharednew.generated.resources.faq_camera_not_appearing_answer
import cameragps.sharednew.generated.resources.faq_camera_not_appearing_question
import cameragps.sharednew.generated.resources.faq_connect_camera_answer
import cameragps.sharednew.generated.resources.faq_connect_camera_question
import cameragps.sharednew.generated.resources.faq_disconnects_answer
import cameragps.sharednew.generated.resources.faq_disconnects_question
import cameragps.sharednew.generated.resources.faq_gps_accuracy_answer
import cameragps.sharednew.generated.resources.faq_gps_accuracy_question
import cameragps.sharednew.generated.resources.faq_permissions_answer
import cameragps.sharednew.generated.resources.faq_permissions_question
import cameragps.sharednew.generated.resources.help_about_description
import cameragps.sharednew.generated.resources.help_about_title
import cameragps.sharednew.generated.resources.help_close_description
import cameragps.sharednew.generated.resources.help_faq_title
import cameragps.sharednew.generated.resources.help_need_more_description
import cameragps.sharednew.generated.resources.help_need_more_title
import cameragps.sharednew.generated.resources.how_about_privacy
import cameragps.sharednew.generated.resources.how_about_privacy_answer
import cameragps.sharednew.generated.resources.is_there_documenation
import cameragps.sharednew.generated.resources.is_there_documenation_answer
import com.sasch.cameragps.sharednew.ui.settings.SharedSettingsScreen
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private data class IosFaqItem(
    val questionRes: StringResource,
    val answerRes: StringResource,
    val containsLinks: Boolean = false,
)

@Composable
internal fun IosHelpScreen(
    onBackClick: () -> Unit,
) {
    val faqItems = listOf(
        IosFaqItem(
            questionRes = Res.string.is_there_documenation,
            answerRes = Res.string.is_there_documenation_answer,
            containsLinks = true,
        ),
        IosFaqItem(
            questionRes = Res.string.how_about_privacy,
            answerRes = Res.string.how_about_privacy_answer,
        ),
        IosFaqItem(
            questionRes = Res.string.faq_connect_camera_question,
            answerRes = Res.string.faq_connect_camera_answer,
        ),
        IosFaqItem(
            questionRes = Res.string.faq_camera_not_appearing_question,
            answerRes = Res.string.faq_camera_not_appearing_answer,
        ),
        IosFaqItem(
            questionRes = Res.string.faq_permissions_question,
            answerRes = Res.string.faq_permissions_answer,
        ),
        IosFaqItem(
            questionRes = Res.string.faq_gps_accuracy_question,
            answerRes = Res.string.faq_gps_accuracy_answer,
        ),
        IosFaqItem(
            questionRes = Res.string.faq_disconnects_question,
            answerRes = Res.string.faq_disconnects_answer,
        ),
    )

    SharedSettingsScreen(
        title = stringResource(Res.string.help_faq_title),
        onBackClick = onBackClick,
        onTitleClick = {},
        navigationIcon = {
            Icon(
                painter = painterResource(Res.drawable.arrow_back_24px),
                contentDescription = stringResource(Res.string.help_close_description),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(Res.string.help_about_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.help_about_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            items(faqItems) { faq ->
                var expanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(faq.questionRes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (expanded) {
                            Spacer(modifier = Modifier.height(8.dp))
                            if (faq.containsLinks) {
                                Text(
                                    text = buildAnnotatedString {
                                        append(stringResource(faq.answerRes))
                                        append("\n")
                                        withLink(
                                            LinkAnnotation.Url(
                                                "https://github.com/Saschl/camera-gps/blob/main/README.md",
                                                TextLinkStyles(
                                                    style = SpanStyle(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        textDecoration = TextDecoration.Underline,
                                                    ),
                                                ),
                                            ),
                                        ) {
                                            append("https://github.com/Saschl/camera-gps")
                                        }
                                        append("\n")
                                        withLink(
                                            LinkAnnotation.Url(
                                                "mailto:saschl.ra@web.de",
                                                TextLinkStyles(
                                                    style = SpanStyle(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        textDecoration = TextDecoration.Underline,
                                                    ),
                                                ),
                                            ),
                                        ) {
                                            append("saschl.ra@web.de")
                                        }
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = stringResource(faq.answerRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(Res.string.help_need_more_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(Res.string.help_need_more_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }
}


